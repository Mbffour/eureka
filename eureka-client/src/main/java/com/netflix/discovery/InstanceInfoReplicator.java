package com.netflix.discovery;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A task for updating and replicating the local instanceinfo to the remote server. Properties of this task are:
 * - configured with a single update thread to guarantee sequential update to the remote server
 * - update tasks can be scheduled on-demand via onDemandUpdate()
 * - task processing is rate limited by burstSize
 * - a new update task is always scheduled automatically after an earlier update task. However if an on-demand task
 *   is started, the scheduled automatic update task is discarded (and a new one will be scheduled after the new
 *   on-demand update).
 *
 *   @author dliu
 */

/**
 *
 *用于更新本地instanceinfo并将其复制到远程服务器的任务。 此任务的属性是：
 * - 配置单个更新线程，以保证对远程服务器的顺序更新
 * - 可以通过onDemandUpdate（）按需安排更新任务
 * - 任务处理受burstSize限制
 * - 在更早的更新任务之后，始终会自动安排新的更新任务。 但是，如果是按需任务
 *启动后，计划的自动更新任务将被丢弃（新计划将在新计划之后安排
 *按需更新）。
 */
class InstanceInfoReplicator implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);

    private final DiscoveryClient discoveryClient;

    /**
     * 应用实例信息
     */
    private final InstanceInfo instanceInfo;

    /**
     * 定时执行频率，单位：秒
     */
    private final int replicationIntervalSeconds;

    /**
     * 定时执行器
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 定时执行任务的 Future
     */
    private final AtomicReference<Future> scheduledPeriodicRef;

    /**
     * 是否开启调度
     */
    private final AtomicBoolean started;

    /**
     * 限流相关
     */
    private final RateLimiter rateLimiter;
    private final int burstSize;
    private final int allowedRatePerMinute;



    InstanceInfoReplicator(DiscoveryClient discoveryClient, InstanceInfo instanceInfo, int replicationIntervalSeconds, int burstSize) {
        this.discoveryClient = discoveryClient;
        this.instanceInfo = instanceInfo;
        /**
         * 定时任务 单线程
         *
         * InstanceInfoReplicator 实例信息复制
         */
        this.scheduler = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("DiscoveryClient-InstanceInfoReplicator-%d")
                        .setDaemon(true)
                        .build());

        this.scheduledPeriodicRef = new AtomicReference<Future>();

        this.started = new AtomicBoolean(false);
        this.rateLimiter = new RateLimiter(TimeUnit.MINUTES);
        this.replicationIntervalSeconds = replicationIntervalSeconds;
        this.burstSize = burstSize;

        this.allowedRatePerMinute = 60 * this.burstSize / this.replicationIntervalSeconds;
        logger.info("InstanceInfoReplicator onDemand update allowed rate per min is {}", allowedRatePerMinute);
    }


    public void start(int initialDelayMs) {
        if (started.compareAndSet(false, true)) {

            // 设置 应用实例信息 数据不一致

            // 初始化 register
            instanceInfo.setIsDirty();  // for initial register

            // 提交任务，并设置该任务的 Future
            Future next = scheduler.schedule(this, initialDelayMs, TimeUnit.SECONDS);
            scheduledPeriodicRef.set(next);
        }
    }

    public void stop() {
        shutdownAndAwaitTermination(scheduler);
        started.set(false);
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("InstanceInfoReplicator stop interrupted");
        }
    }

    /**
     * 状态改变更新 注册或跟新服务
     * @return
     */
    public boolean onDemandUpdate() {
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) {  //限流

            /**
             * 没有关闭
             */
            if (!scheduler.isShutdown()) {
                scheduler.submit(new Runnable() {
                    @Override
                    public void run() {
                        logger.debug("Executing on-demand update of local InstanceInfo");
    
                        Future latestPeriodic = scheduledPeriodicRef.get();
                        if (latestPeriodic != null && !latestPeriodic.isDone()) {
                            //取消最新的预定更新，将在按需更新结束时重新安排
                            logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of on demand update");
                            latestPeriodic.cancel(false);

                            //调用 Future#cancel(false) 方法，取消定时任务，避免无用的注册。
                        }

                        /**
                         * 这里进行了实例信息刷新和注册
                         */
                        InstanceInfoReplicator.this.run();
                    }
                });
                return true;
            } else {
                logger.warn("Ignoring onDemand update due to stopped scheduler");
                return false;
            }
        } else {
            logger.warn("Ignoring onDemand update due to rate limiter");
            return false;
        }
    }

    public void run() {
        try {

            // 刷新 应用实例信息  刷新应用实例信息。此处可能导致应用实例信息数据不一致
            discoveryClient.refreshInstanceInfo();
            // 判断 应用实例信息 是否数据不一致
            Long dirtyTimestamp = instanceInfo.isDirtyWithTime();

            if (dirtyTimestamp != null) {
                /**
                 * 注册Eureka
                 */
                discoveryClient.register();
                // 设置 应用实例信息 数据一致   isInstanceInfoDirty 只为false
                instanceInfo.unsetIsDirty(dirtyTimestamp);
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            /**
             * 下一次任务执行
             */
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);
            scheduledPeriodicRef.set(next);
        }
    }

}
