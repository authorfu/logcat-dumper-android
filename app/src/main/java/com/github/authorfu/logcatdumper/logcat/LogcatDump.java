package com.github.authorfu.logcatdumper.logcat;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * usage:
 * LogcatDump.start(context, new FileLogDumper())
 * LogcatDump.stop() // optional
 */
public class LogcatDump {

    public static class Config implements Cloneable {

        private int runRateAtSeconds = 10;

        private int heartBeatRateAtSeconds = 1;

        /**
         * use adb logcat -h to show format verb option
         * choose one of verb : time or threadtime(if you need thread info)
         * choose multiple of adverbs:  descriptive printable uid usec year zone
         */
        private String formatVerb = "threadtime";

        private String formatAdverb = "year descriptive printable";

        /**
         * Default -b main,system,crash,kernel.
         */
        private String formatBuffer = "main,crash";

        public void setRunRateAtSeconds(int runRateAtSeconds) {
            this.runRateAtSeconds = runRateAtSeconds;
        }

    }

    private static ProcessBuilder dumpProcessBuilder;
    private static ProcessBuilder cleanProcessBuilder;

    private volatile static ScheduledThreadPoolExecutor executor;

    private static final String TAG = "LogcatDumper";

    public static void start(Context context, ILogDumper logDumper) {
        start(context, logDumper, new Config());
    }

    public static void start(Context context, ILogDumper logDumper, Config config) {
        if (executor != null) {
            throw new RuntimeException("please don't call it twice!");
        }
        Log.i(TAG, "start Dumper!");
        Context applicationContext = context.getApplicationContext();

        initProcessBuilders(config);
        startLog(applicationContext, config, logDumper);
    }

    public static void stop() {
        if (executor == null) {
            Log.e(TAG, "LogcatDumper did not start, skip");
            return;
        }
        executor.shutdown();
        executor = null;
        Log.i(TAG, "stop Dumper!");
    }


    private static void initProcessBuilders(Config config) {
        dumpProcessBuilder = new ProcessBuilder("logcat", "-d", "-v",
                config.formatVerb + " " + config.formatAdverb, "-b", config.formatBuffer);
        dumpProcessBuilder.redirectErrorStream(true);
        cleanProcessBuilder = new ProcessBuilder("logcat", "-c");
        cleanProcessBuilder.redirectErrorStream(true);
    }

    private static void startLog(Context applicationContext, Config config, ILogDumper logDumper) {
        executor = new ScheduledThreadPoolExecutor(1);
        executor.submit(() -> {
            onStart(applicationContext, logDumper, config);
        });

        Runnable task = () -> {
            onRun(logDumper);
        };
        executor.scheduleAtFixedRate(task, config.runRateAtSeconds,
                config.runRateAtSeconds, TimeUnit.SECONDS);

        if (config.heartBeatRateAtSeconds > 0) {
            Runnable heartbeatTask = new Runnable() {
                private int count = 0;

                @Override
                public void run() {
                    Log.i(TAG, "logcat dumper heartbeat, " + count++);
                }
            };
            executor.scheduleAtFixedRate(heartbeatTask, config.heartBeatRateAtSeconds,
                    config.heartBeatRateAtSeconds, TimeUnit.SECONDS);
        }
    }


    private static void onStart(Context applicationContext, ILogDumper logDumper, Config config) {
        logDumper.onStart(applicationContext, config);
        try {
            cleanLogcat();
        } catch (IOException | InterruptedException e) {
            logDumper.onException(e);
        }

    }

    private static void onRun(ILogDumper logDumper) {
        try {
            Process p = dumpProcessBuilder.start();
            InputStream is = p.getInputStream();
            logDumper.onInput(is);

            int exitValue = p.waitFor();
            if (exitValue != 0) {
                throw new IOException("logcat -d return non-zero:" + exitValue);
            }
            cleanLogcat();
        } catch (IOException | InterruptedException e) {
            logDumper.onException(e);
        }
    }

    private static void cleanLogcat() throws IOException, InterruptedException {
        Process p = cleanProcessBuilder.start();

        int exitValue = p.waitFor();
        if (exitValue != 0) {
            throw new IOException("logcat -c return non-zero:" + exitValue);
        }
        Log.i(TAG, "logcat -c success");
    }


}
