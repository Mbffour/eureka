package com.netflix.eureka.util.batcher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.netflix.eureka.util.batcher.TaskProcessor.ProcessingResult;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.StatsTimer;
import com.netflix.servo.stats.StatsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.eureka.Names.METRIC_REPLICATION_PREFIX;

/**
 * {@link TaskExecutors} instance holds a number of worker threads that cooperate with {@link AcceptorExecutor}.
 * Each worker sends a job request to {@link AcceptorExecutor} whenever it is available, and processes it once
 * provided with a task(s).
 *
 * @author Tomasz Bak
 */
class TaskExecutors<ID, T> {

    private static final Logger logger = LoggerFactory.getLogger(TaskExecutors.class);

    private final AtomicBoolean isShutdown;

    /**
     * 工作线程池
     */
    private final List<Thread> workerThreads;

    TaskExecutors(WorkerRunnableFactory<ID, T> workerRunnableFactory, int workerCount, AtomicBoolean isShutdown) {
        this.isShutdown = isShutdown;
        this.workerThreads = new ArrayList<>();

        // 创建 工作线程池
        ThreadGroup threadGroup = new ThreadGroup("eurekaTaskExecutors");

        /**
         * 开启工作线程
         */
        for (int i = 0; i < workerCount; i++) {
            WorkerRunnable<ID, T> runnable = workerRunnableFactory.create(i);
            Thread workerThread = new Thread(threadGroup, runnable, runnable.getWorkerName());
            workerThreads.add(workerThread);
            workerThread.setDaemon(true);

            //开始
            workerThread.start();
        }
    }

    void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            for (Thread workerThread : workerThreads) {
                workerThread.interrupt();
            }
        }
    }

    static <ID, T> TaskExecutors<ID, T> singleItemExecutors(final String name,
                                                            int workerCount,
                                                            final TaskProcessor<T> processor,
                                                            final AcceptorExecutor<ID, T> acceptorExecutor) {
        final AtomicBoolean isShutdown = new AtomicBoolean();
        final TaskExecutorMetrics metrics = new TaskExecutorMetrics(name);
        return new TaskExecutors<>(new WorkerRunnableFactory<ID, T>() {
            @Override
            public WorkerRunnable<ID, T> create(int idx) {
                return new SingleTaskWorkerRunnable<>("TaskNonBatchingWorker-" + name + '-' + idx, isShutdown, metrics, processor, acceptorExecutor);
            }
        }, workerCount, isShutdown);
    }

    static <ID, T> TaskExecutors<ID, T> batchExecutors(final String name,
                                                       int workerCount,
                                                       final TaskProcessor<T> processor,
                                                       final AcceptorExecutor<ID, T> acceptorExecutor) {
        final AtomicBoolean isShutdown = new AtomicBoolean();
        final TaskExecutorMetrics metrics = new TaskExecutorMetrics(name);


        return new TaskExecutors<>(new WorkerRunnableFactory<ID, T>() {
            @Override
            public WorkerRunnable<ID, T> create(int idx) {
                /**
                 * 创建任务
                 */
                return new BatchWorkerRunnable<>("TaskBatchingWorker-" +name + '-' + idx, isShutdown, metrics, processor, acceptorExecutor);
            }
        }, workerCount, isShutdown);
    }

    static class TaskExecutorMetrics {

        @Monitor(name = METRIC_REPLICATION_PREFIX + "numberOfSuccessfulExecutions", description = "Number of successful task executions", type = DataSourceType.COUNTER)
        volatile long numberOfSuccessfulExecutions;

        @Monitor(name = METRIC_REPLICATION_PREFIX + "numberOfTransientErrors", description = "Number of transient task execution errors", type = DataSourceType.COUNTER)
        volatile long numberOfTransientError;

        @Monitor(name = METRIC_REPLICATION_PREFIX + "numberOfPermanentErrors", description = "Number of permanent task execution errors", type = DataSourceType.COUNTER)
        volatile long numberOfPermanentError;

        @Monitor(name = METRIC_REPLICATION_PREFIX + "numberOfCongestionIssues", description = "Number of congestion issues during task execution", type = DataSourceType.COUNTER)
        volatile long numberOfCongestionIssues;

        final StatsTimer taskWaitingTimeForProcessing;

        TaskExecutorMetrics(String id) {
            final double[] percentiles = {50.0, 95.0, 99.0, 99.5};
            final StatsConfig statsConfig = new StatsConfig.Builder()
                    .withSampleSize(1000)
                    .withPercentiles(percentiles)
                    .withPublishStdDev(true)
                    .build();
            final MonitorConfig config = MonitorConfig.builder(METRIC_REPLICATION_PREFIX + "executionTime").build();
            taskWaitingTimeForProcessing = new StatsTimer(config, statsConfig);

            try {
                Monitors.registerObject(id, this);
            } catch (Throwable e) {
                logger.warn("Cannot register servo monitor for this object", e);
            }
        }

        void registerTaskResult(ProcessingResult result, int count) {
            switch (result) {
                case Success:
                    numberOfSuccessfulExecutions += count;
                    break;
                case TransientError:
                    numberOfTransientError += count;
                    break;
                case PermanentError:
                    numberOfPermanentError += count;
                    break;
                case Congestion:
                    numberOfCongestionIssues += count;
                    break;
            }
        }

        <ID, T> void registerExpiryTime(TaskHolder<ID, T> holder) {
            taskWaitingTimeForProcessing.record(System.currentTimeMillis() - holder.getSubmitTimestamp(), TimeUnit.MILLISECONDS);
        }

        <ID, T> void registerExpiryTimes(List<TaskHolder<ID, T>> holders) {
            long now = System.currentTimeMillis();
            for (TaskHolder<ID, T> holder : holders) {
                taskWaitingTimeForProcessing.record(now - holder.getSubmitTimestamp(), TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * 创建工作线程工厂
     *
     * @param <ID> 任务编号泛型
     * @param <T> 批量任务执行器
     */
    interface WorkerRunnableFactory<ID, T> {
        WorkerRunnable<ID, T> create(int idx);
    }

    /**
     * 抽象工作线程
     * @param <ID>
     * @param <T>
     */
    abstract static class WorkerRunnable<ID, T> implements Runnable {
        final String workerName;  //线程名
        final AtomicBoolean isShutdown;
        final TaskExecutorMetrics metrics;
        final TaskProcessor<T> processor;  //任务处理器
        final AcceptorExecutor<ID, T> taskDispatcher;   //任务接收执行器

        WorkerRunnable(String workerName,
                       AtomicBoolean isShutdown,
                       TaskExecutorMetrics metrics,
                       TaskProcessor<T> processor,
                       AcceptorExecutor<ID, T> taskDispatcher) {
            this.workerName = workerName;
            this.isShutdown = isShutdown;
            this.metrics = metrics;
            this.processor = processor;
            this.taskDispatcher = taskDispatcher;
        }

        String getWorkerName() {
            return workerName;
        }
    }

    static class BatchWorkerRunnable<ID, T> extends WorkerRunnable<ID, T> {

        BatchWorkerRunnable(String workerName,
                            AtomicBoolean isShutdown,
                            TaskExecutorMetrics metrics,
                            TaskProcessor<T> processor,
                            AcceptorExecutor<ID, T> acceptorExecutor) {
            super(workerName, isShutdown, metrics, processor, acceptorExecutor);
        }

        /**
         * 执行的任务方法
         */
        @Override
        public void run() {
            try {
                while (!isShutdown.get()) {
                    // 注释：获取信号量释放 batchWorkRequests.release()，返回任务集合列表
                    //首先要获取信号量释放，才能获得任务集合
                    List<TaskHolder<ID, T>> holders = getWork();

                    /**
                     * 记录过期时间
                     */
                    metrics.registerExpiryTimes(holders);

                    // 获得实际批量任务
                    List<T> tasks = getTasksOf(holders);

                    // 注释：将批量任务打包请求Peer节点
                    ProcessingResult result = processor.process(tasks);


                    switch (result) {
                        case Success:
                            break;
                        case Congestion:
                        case TransientError:
                            //任务接收器
                            taskDispatcher.reprocess(holders, result);  // 提交重新处理
                            break;
                        case PermanentError:
                            logger.warn("Discarding {} tasks of {} due to permanent error", holders.size(), workerName);
                    }
                    metrics.registerTaskResult(result, tasks.size());
                }
            } catch (InterruptedException e) {
                // Ignore
            } catch (Throwable e) {
                // Safe-guard, so we never exit this loop in an uncontrolled way.
                logger.warn("Discovery WorkerThread error", e);
            }
        }

        /**
         * 取队列 首先要获取信号量释放，才能获得任务集合
         * @return
         * @throws InterruptedException
         */
        private List<TaskHolder<ID, T>> getWork() throws InterruptedException {
            /**
             * 释放一个信号量  然后一直等获取到一个任务
             */
            BlockingQueue<List<TaskHolder<ID, T>>> workQueue = taskDispatcher.requestWorkItems();
            List<TaskHolder<ID, T>> result;

            //获取一个批量任务直到成功
            do {
                result = workQueue.poll(1, TimeUnit.SECONDS);
            } while (!isShutdown.get() && result == null);
            return (result == null) ? new ArrayList<>() : result;
        }


        private List<T> getTasksOf(List<TaskHolder<ID, T>> holders) {
            List<T> tasks = new ArrayList<>(holders.size());
            for (TaskHolder<ID, T> holder : holders) {
                tasks.add(holder.getTask());
            }
            return tasks;
        }
    }

    static class SingleTaskWorkerRunnable<ID, T> extends WorkerRunnable<ID, T> {

        SingleTaskWorkerRunnable(String workerName,
                                 AtomicBoolean isShutdown,
                                 TaskExecutorMetrics metrics,
                                 TaskProcessor<T> processor,
                                 AcceptorExecutor<ID, T> acceptorExecutor) {
            super(workerName, isShutdown, metrics, processor, acceptorExecutor);
        }

        @Override
        public void run() {
            try {
                while (!isShutdown.get()) {

                    // 发起请求信号量，并获得单任务的工作队列
                    BlockingQueue<TaskHolder<ID, T>> workQueue = taskDispatcher.requestWorkItem();


                    TaskHolder<ID, T> taskHolder;

                    // 【循环】获取单任务，直到成功
                    while ((taskHolder = workQueue.poll(1, TimeUnit.SECONDS)) == null) {
                        if (isShutdown.get()) {
                            return;
                        }
                    }

                    // TODO 芋艿：监控相关，暂时无视
                    metrics.registerExpiryTime(taskHolder);
                    if (taskHolder != null) {

                        // 调用处理器执行任务
                        ProcessingResult result = processor.process(taskHolder.getTask());
                        switch (result) {
                            case Success:
                                break;
                            case Congestion:
                            case TransientError:
                                taskDispatcher.reprocess(taskHolder, result);
                                break;
                            case PermanentError:
                                logger.warn("Discarding a task of {} due to permanent error", workerName);
                        }
                        metrics.registerTaskResult(result, 1);
                    }
                }
            } catch (InterruptedException e) {
                // Ignore
            } catch (Throwable e) {
                // Safe-guard, so we never exit this loop in an uncontrolled way.
                logger.warn("Discovery WorkerThread error", e);
            }
        }
    }
}
