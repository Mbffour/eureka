/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.registry;

import javax.annotation.Nullable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.common.cache.CacheBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.ActionType;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.Pair;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.lease.Lease;
import com.netflix.eureka.registry.rule.InstanceStatusOverrideRule;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.MeasuredRate;
import com.netflix.servo.annotations.DataSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.eureka.util.EurekaMonitors.*;

/**
 * Handles all registry requests from eureka clients.
 *
 * <p>
 * Primary operations that are performed are the
 * <em>Registers</em>, <em>Renewals</em>, <em>Cancels</em>, <em>Expirations</em>, and <em>Status Changes</em>. The
 * registry also stores only the delta operations
 * </p>
 *
 * @author Karthik Ranganathan
 *
 */
public abstract class AbstractInstanceRegistry implements InstanceRegistry {
    private static final Logger logger = LoggerFactory.getLogger(AbstractInstanceRegistry.class);

    private static final String[] EMPTY_STR_ARRAY = new String[0];
    private final ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>> registry
            = new ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>>();

    /**
     * 远程区域名
     */
    protected Map<String, RemoteRegionRegistry> regionNameVSRemoteRegistry = new HashMap<String, RemoteRegionRegistry>();

    /*
     应用实例 覆盖状态
     */
    protected final ConcurrentMap<String, InstanceStatus> overriddenInstanceStatusMap = CacheBuilder
            .newBuilder().initialCapacity(500)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .<String, InstanceStatus>build().asMap();

    // CircularQueues here for debugging/statistics purposes only
    private final CircularQueue<Pair<Long, String>> recentRegisteredQueue;
    private final CircularQueue<Pair<Long, String>> recentCanceledQueue;

    /**
     * 最近租约变更记录队列
     *
     * 当应用实例注册、下线、状态变更时，创建最近租约变更记录( RecentlyChangedItem ) 到队列。
     */
    private ConcurrentLinkedQueue<RecentlyChangedItem> recentlyChangedQueue = new ConcurrentLinkedQueue<RecentlyChangedItem>();

    private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Lock read = readWriteLock.readLock();
    private final Lock write = readWriteLock.writeLock();
    protected final Object lock = new Object();

    private Timer deltaRetentionTimer = new Timer("Eureka-DeltaRetentionTimer", true);
    private Timer evictionTimer = new Timer("Eureka-EvictionTimer", true);
    private final MeasuredRate renewsLastMin;

    private final AtomicReference<EvictionTask> evictionTaskRef = new AtomicReference<EvictionTask>();

    protected String[] allKnownRemoteRegions = EMPTY_STR_ARRAY;


    /**
     * 期望最小每分钟续租次数
     */
    protected volatile int numberOfRenewsPerMinThreshold;

    /**
     * 期望最大每分钟续租次数
     */
    protected volatile int expectedNumberOfClientsSendingRenews;

    protected final EurekaServerConfig serverConfig;
    protected final EurekaClientConfig clientConfig;
    protected final ServerCodecs serverCodecs;
    protected volatile ResponseCache responseCache;

    /**
     * Create a new, empty instance registry.
     */
    protected AbstractInstanceRegistry(EurekaServerConfig serverConfig, EurekaClientConfig clientConfig, ServerCodecs serverCodecs) {
        this.serverConfig = serverConfig;
        this.clientConfig = clientConfig;
        this.serverCodecs = serverCodecs;
        this.recentCanceledQueue = new CircularQueue<Pair<Long, String>>(1000);
        this.recentRegisteredQueue = new CircularQueue<Pair<Long, String>>(1000);

        /**
         * 60秒间隔
         */
        this.renewsLastMin = new MeasuredRate(1000 * 60 * 1);

        /**
         * 最近租约任务 定时删除
         */
        this.deltaRetentionTimer.schedule(getDeltaRetentionTask(),
                serverConfig.getDeltaRetentionTimerIntervalInMs(),
                serverConfig.getDeltaRetentionTimerIntervalInMs());
    }

    @Override
    public synchronized void initializedResponseCache() {
        if (responseCache == null) {
            responseCache = new ResponseCacheImpl(serverConfig, serverCodecs, this);
        }
    }

    protected void initRemoteRegionRegistry() throws MalformedURLException {
        Map<String, String> remoteRegionUrlsWithName = serverConfig.getRemoteRegionUrlsWithName();
        if (!remoteRegionUrlsWithName.isEmpty()) {
            allKnownRemoteRegions = new String[remoteRegionUrlsWithName.size()];
            int remoteRegionArrayIndex = 0;
            for (Map.Entry<String, String> remoteRegionUrlWithName : remoteRegionUrlsWithName.entrySet()) {
                RemoteRegionRegistry remoteRegionRegistry = new RemoteRegionRegistry(
                        serverConfig,
                        clientConfig,
                        serverCodecs,
                        remoteRegionUrlWithName.getKey(),
                        new URL(remoteRegionUrlWithName.getValue()));
                regionNameVSRemoteRegistry.put(remoteRegionUrlWithName.getKey(), remoteRegionRegistry);
                allKnownRemoteRegions[remoteRegionArrayIndex++] = remoteRegionUrlWithName.getKey();
            }
        }
        logger.info("Finished initializing remote region registries. All known remote regions: {}",
                (Object) allKnownRemoteRegions);
    }

    @Override
    public ResponseCache getResponseCache() {
        return responseCache;
    }

    public long getLocalRegistrySize() {
        long total = 0;
        for (Map<String, Lease<InstanceInfo>> entry : registry.values()) {
            total += entry.size();
        }
        return total;
    }

    /**
     * Completely clear the registry.
     */
    @Override
    public void clearRegistry() {
        overriddenInstanceStatusMap.clear();
        recentCanceledQueue.clear();
        recentRegisteredQueue.clear();
        recentlyChangedQueue.clear();
        registry.clear();
    }

    // for server info use
    @Override
    public Map<String, InstanceStatus> overriddenInstanceStatusesSnapshot() {
        return new HashMap<>(overriddenInstanceStatusMap);
    }

    /**
     * Registers a new instance with a given duration.
     *
     * @see com.netflix.eureka.lease.LeaseManager#register(java.lang.Object, int, boolean)
     */
    public void register(InstanceInfo registrant, int leaseDuration, boolean isReplication) {
        try {
            read.lock();
            /**
             * // 注释：registry　这个变量，就是我们所谓的注册表，注册表是保存在内存中的；
             *
             * key appname
             */
            Map<String, Lease<InstanceInfo>> gMap = registry.get(registrant.getAppName());

            // 增加 注册次数 到 监控
            REGISTER.increment(isReplication);

            // 获得 应用实例信息 对应的 租约
            if (gMap == null) {
                final ConcurrentHashMap<String, Lease<InstanceInfo>> gNewMap = new ConcurrentHashMap<String, Lease<InstanceInfo>>();
                gMap = registry.putIfAbsent(registrant.getAppName(), gNewMap);// 添加 应用
                if (gMap == null) {// 添加 应用成功
                    gMap = gNewMap;
                }
            }

            /**
             * 获取租约
             */
            Lease<InstanceInfo> existingLease = gMap.get(registrant.getId());

            // Retain the last dirty timestamp without overwriting it, if there is already a lease

            /**
             * 已存在时，使用数据不一致的时间大的应用注册信息为有效的
              */
            if (existingLease != null && (existingLease.getHolder() != null)) {

                // Server 注册的 InstanceInfo
                Long existingLastDirtyTimestamp = existingLease.getHolder().getLastDirtyTimestamp();

                // Client 请求的 InstanceInfo
                Long registrationLastDirtyTimestamp = registrant.getLastDirtyTimestamp();


                logger.debug("Existing lease found (existing={}, provided={}", existingLastDirtyTimestamp, registrationLastDirtyTimestamp);

                // this is a > instead of a >= because if the timestamps are equal, we still take the remote transmitted
                // InstanceInfo instead of the server local copy.

                /**
                 *
                 * 当租约已存在，判断 Server 已存在的 InstanceInfo 的 lastDirtyTimestamp 是否大于( 不包括等于 )
                 * Client 请求的 InstanceInfo ，若是，使用 Server 的 InstanceInfo 进行替代。
                 */
                if (existingLastDirtyTimestamp > registrationLastDirtyTimestamp) {
                    logger.warn("There is an existing lease and the existing lease's dirty timestamp {} is greater" +
                            " than the one that is being registered {}", existingLastDirtyTimestamp, registrationLastDirtyTimestamp);
                    logger.warn("Using the existing instanceInfo instead of the new instanceInfo as the registrant");
                    registrant = existingLease.getHolder();
                }
            } else {

                /**
                 * existingLease = null
                 * // 【自我保护机制】增加 `numberOfRenewsPerMinThreshold` 、`expectedNumberOfRenewsPerMin`
                 * 注册新服务增加
                 */

// 1.9.3
//                if (this.expectedNumberOfRenewsPerMin > 0) {
//                    // Since the client wants to cancel it, reduce the threshold
//                    // (1
//                    // for 30 seconds, 2 for a minute)
//                    this.expectedNumberOfRenewsPerMin = this.expectedNumberOfRenewsPerMin + 2;
//                    this.numberOfRenewsPerMinThreshold =
//                            (int) (this.expectedNumberOfRenewsPerMin * serverConfig.getRenewalPercentThreshold());
//                }

                // The lease does not exist and hence it is a new registration
                synchronized (lock) {
                    if (this.expectedNumberOfClientsSendingRenews > 0) {

                        // Since the client wants to cancel it, reduce the threshold
                        // (1
                        // for 30 seconds, 2 for a minute)

                        /**
                         * 新服务 加一
                         */
                        this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews + 1;

                        /**
                         * 设置numberOfRenewsPerMinThreshold 值
                         *
                         */
                        updateRenewsPerMinThreshold();
                    }
                }
                logger.debug("No previous lease information found; it is new registration");
            }

            // 创建 租约
            Lease<InstanceInfo> lease = new Lease<InstanceInfo>(registrant, leaseDuration);
            if (existingLease != null) {
                // 若租约已存在，设置 租约的开始服务的时间戳
                lease.setServiceUpTimestamp(existingLease.getServiceUpTimestamp());
            }

            // 添加到 租约映射
            gMap.put(registrant.getId(), lease);

            // 添加到 最近注册的调试队列   页面用
            synchronized (recentRegisteredQueue) {
                recentRegisteredQueue.add(new Pair<Long, String>(
                        System.currentTimeMillis(),
                        registrant.getAppName() + "(" + registrant.getId() + ")"));
            }

            // 添加到 应用实例覆盖状态映射（Eureka-Server 初始化使用）
            // This is where the initial state transfer of overridden status happens
            if (!InstanceStatus.UNKNOWN.equals(registrant.getOverriddenStatus())) {
                logger.debug("Found overridden status {} for instance {}. Checking to see if needs to be add to the "
                                + "overrides", registrant.getOverriddenStatus(), registrant.getId());
                if (!overriddenInstanceStatusMap.containsKey(registrant.getId())) {
                    logger.info("Not found overridden id {} and hence adding it", registrant.getId());
                    overriddenInstanceStatusMap.put(registrant.getId(), registrant.getOverriddenStatus());
                }
            }

            //设置应用实例的覆盖状态( overridestatus )，避免注册应用实例后，丢失覆盖状态
            InstanceStatus overriddenStatusFromMap = overriddenInstanceStatusMap.get(registrant.getId());


            if (overriddenStatusFromMap != null) {
                logger.info("Storing overridden status {} from map", overriddenStatusFromMap);

                //设置覆盖状态
                registrant.setOverriddenStatus(overriddenStatusFromMap);
            }

            /**
             * 获得应用实例最终状态，并设置应用实例的状态
              */
            // Set the status based on the overridden status rules
            InstanceStatus overriddenInstanceStatus = getOverriddenInstanceStatus(registrant, existingLease, isReplication);

            // 设置 应用实例状态
            registrant.setStatusWithoutDirty(overriddenInstanceStatus);

            // 设置 租约的开始服务的时间戳（只有第一次有效）
            // If the lease is registered with UP status, set lease service up timestamp
            if (InstanceStatus.UP.equals(registrant.getStatus())) {
                lease.serviceUp();
            }

            // 设置 应用实例信息的操作类型 为 添加
            registrant.setActionType(ActionType.ADDED);

            // 添加到 最近租约变更记录队列 增量获取用到
            recentlyChangedQueue.add(new RecentlyChangedItem(lease));

            // 设置 租约的最后更新时间戳
            registrant.setLastUpdatedTimestamp();

            // 设置 响应缓存 过期
            invalidateCache(registrant.getAppName(), registrant.getVIPAddress(), registrant.getSecureVipAddress());
            logger.info("Registered instance {}/{} with status {} (replication={})",
                    registrant.getAppName(), registrant.getId(), registrant.getStatus(), isReplication);
        } finally {
            read.unlock();
        }
    }

    /**
     * Cancels the registration of an instance.
     *
     * <p>
     * This is normally invoked by a client when it shuts down informing the
     * server to remove the instance from traffic.
     * </p>
     *
     * @param appName the application name of the application.
     * @param id the unique identifier of the instance.
     * @param isReplication true if this is a replication event from other nodes, false
     *                      otherwise.
     * @return true if the instance was removed from the {@link AbstractInstanceRegistry} successfully, false otherwise.
     */
    @Override
    public boolean cancel(String appName, String id, boolean isReplication) {
        return internalCancel(appName, id, isReplication);
    }

    /**
     * {@link #cancel(String, String, boolean)} method is overridden by {@link PeerAwareInstanceRegistry}, so each
     * cancel request is replicated to the peers. This is however not desired for expires which would be counted
     * in the remote peers as valid cancellations, so self preservation mode would not kick-in.
     */
    protected boolean internalCancel(String appName, String id, boolean isReplication) {
        try {
            /**
             * 读锁
             */
            read.lock();
            // 增加 取消注册次数 到 监控
            CANCEL.increment(isReplication);

            // 移除 租约映射
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> leaseToCancel = null;
            if (gMap != null) {
                leaseToCancel = gMap.remove(id);
            }

            // 添加到 最近取消注册的调试队列
            synchronized (recentCanceledQueue) {
                recentCanceledQueue.add(new Pair<Long, String>(System.currentTimeMillis(), appName + "(" + id + ")"));
            }

            // 移除 应用实例覆盖状态映射
            InstanceStatus instanceStatus = overriddenInstanceStatusMap.remove(id);
            if (instanceStatus != null) {
                logger.debug("Removed instance id {} from the overridden map which has value {}", id, instanceStatus.name());
            }

            // 租约不存在
            if (leaseToCancel == null) {
                CANCEL_NOT_FOUND.increment(isReplication);
                logger.warn("DS: Registry: cancel failed because Lease is not registered for: {}/{}", appName, id);
                return false;
            } else {

                // 设置 租约的取消注册时间戳
                leaseToCancel.cancel();

                /**
                 * 添加到 最近租约变更记录队列
                 * 设置应用实例信息的操作类型为添加，并添加到最近租约变更记录队列( recentlyChangedQueue )。
                 * recentlyChangedQueue 用于注册信息的增量获取
                 */
                InstanceInfo instanceInfo = leaseToCancel.getHolder();
                String vip = null;
                String svip = null;
                if (instanceInfo != null) {
                    instanceInfo.setActionType(ActionType.DELETED);

                    // 添加到 最近租约变更记录队列 增量获取用到
                    recentlyChangedQueue.add(new RecentlyChangedItem(leaseToCancel));
                    instanceInfo.setLastUpdatedTimestamp();
                    vip = instanceInfo.getVIPAddress();
                    svip = instanceInfo.getSecureVipAddress();
                }

                // 设置 响应缓存 过期
                invalidateCache(appName, vip, svip);
                logger.info("Cancelled instance {}/{} (replication={})", appName, id, isReplication);
                return true;
            }
        } finally {
            read.unlock();
        }
    }

    /**
     * Marks the given instance of the given app name as renewed, and also marks whether it originated from
     * replication.
     *
     * @see com.netflix.eureka.lease.LeaseManager#renew(java.lang.String, java.lang.String, boolean)
     */

    /**
     * 将给定应用程序名称的给定实例标记为已更新，并标记它是否源自 复制。
     * renew 来源于 心跳包
     * @param appName
     * @param id
     *            - unique id within appName
     * @param isReplication
     *            - whether this is a replicated entry from another ds node
     * @return
     */

    //#renew(...) 和 #statusUpdate(读锁...) 可以并行执行
    public boolean renew(String appName, String id, boolean isReplication) {

        //增加 续租次数 到 监控
        RENEW.increment(isReplication);
        // 获得 租约
        Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);

        Lease<InstanceInfo> leaseToRenew = null;
        if (gMap != null) {
            leaseToRenew = gMap.get(id);
        }
        /**
         * 如果没有该服务  需要重新注册
         *
         * 当满足下面任意条件，Eureka-Server 返回 404 状态码
         * 租约不存在
         * 返回404
         * 发起重新注册
         */
        if (leaseToRenew == null) {
            RENEW_NOT_FOUND.increment(isReplication);
            logger.warn("DS: Registry: lease doesn't exist, registering resource: {} - {}", appName, id);
            return false;
        } else {

            /**
             * 服务在的处理
             */
            InstanceInfo instanceInfo = leaseToRenew.getHolder();
            if (instanceInfo != null) {
                // touchASGCache(instanceInfo.getASGName());

                /**
                 *  查看覆盖的实例状态
                 */
                InstanceStatus overriddenInstanceStatus = this.getOverriddenInstanceStatus(
                        instanceInfo, leaseToRenew, isReplication);


                /**
                 *  // 应用实例状态未知，无法续约
                 */
                if (overriddenInstanceStatus == InstanceStatus.UNKNOWN) {
                    /**
                     * 实例状态UNKNOWN可能是由于删除覆盖的实例，例如{}“ id +“;需要重新注册”
                     */
                    logger.info("Instance status UNKNOWN possibly due to deleted override for instance {}"
                            + "; re-register required", instanceInfo.getId());
                    RENEW_NOT_FOUND.increment(isReplication);
                    return false;
                }

                //设置实例状态

                /**
                 * 恰巧 #statusUpdate(...) 执行完更新应用实例状态 newStatus，又恰好两者不相等，
                 * 使用 overriddenInstanceStatus 覆盖掉应用实例的 newStatus 状态
                 */
                if (!instanceInfo.getStatus().equals(overriddenInstanceStatus)) {
                    /**
                     * 实例状态{}与覆盖的实例状态{}不同，例如{}。“因此将状态设置为覆盖状态
                     */
                    logger.info(
                            "The instance status {} is different from overridden instance status {} for instance {}. "
                                    + "Hence setting the status to overridden status", instanceInfo.getStatus().name(),
                                    instanceInfo.getOverriddenStatus().name(),
                                    instanceInfo.getId());

                    /**
                     * 实例状态设置
                     */
                    instanceInfo.setStatusWithoutDirty(overriddenInstanceStatus);

                }
            }
            // 新增 续租每分钟次数
            renewsLastMin.increment();
            // 设置 租约最后更新时间（续租）
            leaseToRenew.renew();
            return true;
        }
    }

    /**
     * @deprecated this is expensive, try not to use. See if you can use
     * {@link #storeOverriddenStatusIfRequired(String, String, InstanceStatus)} instead.
     *
     * Stores overridden status if it is not already there. This happens during
     * a reconciliation process during renewal requests.
     *
     * @param id the unique identifier of the instance.
     * @param overriddenStatus Overridden status if any.
     */
    @Deprecated
    @Override
    public void storeOverriddenStatusIfRequired(String id, InstanceStatus overriddenStatus) {
        InstanceStatus instanceStatus = overriddenInstanceStatusMap.get(id);
        if ((instanceStatus == null)
                || (!overriddenStatus.equals(instanceStatus))) {
            // We might not have the overridden status if the server got restarted -this will help us maintain
            // the overridden state from the replica
            logger.info(
                    "Adding overridden status for instance id {} and the value is {}",
                    id, overriddenStatus.name());
            overriddenInstanceStatusMap.put(id, overriddenStatus);
            List<InstanceInfo> instanceInfo = this.getInstancesById(id, false);
            if ((instanceInfo != null) && (!instanceInfo.isEmpty())) {
                instanceInfo.iterator().next().setOverriddenStatus(overriddenStatus);
                logger.info(
                        "Setting the overridden status for instance id {} and the value is {} ",
                        id, overriddenStatus.name());

            }
        }
    }

    /**
     * Stores overridden status if it is not already there. This happens during
     * a reconciliation process during renewal requests.
     *
     * @param appName the application name of the instance.
     * @param id the unique identifier of the instance.
     * @param overriddenStatus overridden status if any.
     */
    @Override
    public void storeOverriddenStatusIfRequired(String appName, String id, InstanceStatus overriddenStatus) {
        InstanceStatus instanceStatus = overriddenInstanceStatusMap.get(id);
        if ((instanceStatus == null) || (!overriddenStatus.equals(instanceStatus))) {
            // We might not have the overridden status if the server got
            // restarted -this will help us maintain the overridden state
            // from the replica
            logger.info("Adding overridden status for instance id {} and the value is {}",
                    id, overriddenStatus.name());
            overriddenInstanceStatusMap.put(id, overriddenStatus);
            InstanceInfo instanceInfo = this.getInstanceByAppAndId(appName, id, false);
            instanceInfo.setOverriddenStatus(overriddenStatus);
            logger.info("Set the overridden status for instance (appname:{}, id:{}} and the value is {} ",
                    appName, id, overriddenStatus.name());
        }
    }

    /**
     * Updates the status of an instance. Normally happens to put an instance
     * between {@link InstanceStatus#OUT_OF_SERVICE} and
     * {@link InstanceStatus#UP} to put the instance in and out of traffic.
     *
     * @param appName the application name of the instance.
     * @param id the unique identifier of the instance.
     * @param newStatus the new {@link InstanceStatus}.
     * @param lastDirtyTimestamp last timestamp when this instance information was updated.
     * @param isReplication true if this is a replication event from other nodes, false
     *                      otherwise.
     * @return true if the status was successfully updated, false otherwise.
     */
    @Override
    public boolean statusUpdate(String appName, String id,
                                InstanceStatus newStatus, String lastDirtyTimestamp,
                                boolean isReplication) {
        try {
            // 获取读锁
            read.lock();
            // 添加 覆盖状态变更次数 到 监控
            STATUS_UPDATE.increment(isReplication);
            // 获得 租约
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> lease = null;
            if (gMap != null) {
                lease = gMap.get(id);
            }
            // 租约不存在
            if (lease == null) {
                return false;
            } else {
                // 设置 租约最后更新时间（续租）
                lease.renew();
                // 应用实例信息不存在( 防御型编程 )
                InstanceInfo info = lease.getHolder();
                // Lease is always created with its instance info object.
                // This log statement is provided as a safeguard, in case this invariant is violated.
                if (info == null) {
                    logger.error("Found Lease without a holder for instance id {}", id);
                }

                /**
                 * 应用实例当前状态和覆该状态不一致时才更新覆盖状态。
                 */
                if ((info != null) && !(info.getStatus().equals(newStatus))) {
                    // Mark service as UP if needed

                    // 当覆盖状态是 InstanceStatus.UP 设置 租约的开始服务的时间戳（只有第一次有效）
                    if (InstanceStatus.UP.equals(newStatus)) {
                        lease.serviceUp();
                    }
                    // This is NAC overriden status

                    // 添加到 应用实例覆盖状态映射
                    overriddenInstanceStatusMap.put(id, newStatus);
                    // Set it for transfer of overridden status to replica on
                    // replica start up

                    /**
                     * 设置 应用实例覆盖状态  用于 Eureka-Server 集群同步
                     */
                    info.setOverriddenStatus(newStatus);

                    // 设置 应用实例信息 数据不一致时间
                    long replicaDirtyTimestamp = 0;

                    // 设置 应用实例状态
                    info.setStatusWithoutDirty(newStatus);

                    if (lastDirtyTimestamp != null) {
                        replicaDirtyTimestamp = Long.valueOf(lastDirtyTimestamp);
                    }
                    // If the replication's dirty timestamp is more than the existing one, just update
                    // it to the replica's.
                    if (replicaDirtyTimestamp > info.getLastDirtyTimestamp()) {
                        info.setLastDirtyTimestamp(replicaDirtyTimestamp);
                    }

                    //类型为修改
                    info.setActionType(ActionType.MODIFIED);

                    /**
                     * 状态变更 添加到最近变更记录中
                     */
                    recentlyChangedQueue.add(new RecentlyChangedItem(lease));

                    // 设置 最后更新时间
                    info.setLastUpdatedTimestamp();
                    // 设置 响应缓存 过期
                    invalidateCache(appName, info.getVIPAddress(), info.getSecureVipAddress());
                }
                return true;
            }
        } finally {
            read.unlock();
        }
    }

    /**
     * Removes status override for a give instance.
     *
     * @param appName the application name of the instance.
     * @param id the unique identifier of the instance.
     * @param newStatus the new {@link InstanceStatus}.
     * @param lastDirtyTimestamp last timestamp when this instance information was updated.
     * @param isReplication true if this is a replication event from other nodes, false
     *                      otherwise.
     * @return true if the status was successfully updated, false otherwise.
     */
    @Override
    public boolean deleteStatusOverride(String appName, String id,
                                        InstanceStatus newStatus,
                                        String lastDirtyTimestamp,
                                        boolean isReplication) {
        try {
            // 获取读锁
            read.lock();
            // 添加 覆盖状态删除次数 到 监控
            STATUS_OVERRIDE_DELETE.increment(isReplication);

            // 获得 租约
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> lease = null;
            if (gMap != null) {
                lease = gMap.get(id);
            }

            // 租约不存在
            if (lease == null) {
                return false;
            } else {

                // 设置 租约最后更新时间（续租）
                lease.renew();
                InstanceInfo info = lease.getHolder();

                // Lease is always created with its instance info object.
                // This log statement is provided as a safeguard, in case this invariant is violated.
                // 应用实例信息不存在( 防御型编程 )
                if (info == null) {
                    logger.error("Found Lease without a holder for instance id {}", id);
                }


                /**
                 * 应用实例的覆盖状态存在才设置状态
                 *
                 * 移除 应用实例覆盖状态
                 */
                InstanceStatus currentOverride = overriddenInstanceStatusMap.remove(id);
                if (currentOverride != null && info != null) {

                    // 设置 应用实例覆盖状态 UNKNOWN 用于 Eureka-Server 集群同步。
                    info.setOverriddenStatus(InstanceStatus.UNKNOWN);

                    // 设置 应用实例状态
                    //设置后，Eureka-Client 拉取注册信息，被更新覆盖状态的应用实例就是设置的状态
                    info.setStatusWithoutDirty(newStatus);

                    // 设置 应用实例信息 数据不一致时间 用于 Eureka-Server 集群同步。
                    long replicaDirtyTimestamp = 0;
                    if (lastDirtyTimestamp != null) {
                        replicaDirtyTimestamp = Long.valueOf(lastDirtyTimestamp);
                    }
                    // If the replication's dirty timestamp is more than the existing one, just update
                    // it to the replica's.
                    if (replicaDirtyTimestamp > info.getLastDirtyTimestamp()) {
                        info.setLastDirtyTimestamp(replicaDirtyTimestamp);
                    }
                    info.setActionType(ActionType.MODIFIED);
                    // 添加到 最近租约变更记录队列 增量获取用到 修改
                    recentlyChangedQueue.add(new RecentlyChangedItem(lease));

                    // 设置 最后更新时间
                    info.setLastUpdatedTimestamp();

                    // 设置 响应缓存 过期
                    invalidateCache(appName, info.getVIPAddress(), info.getSecureVipAddress());
                }
                return true;
            }
        } finally {
            read.unlock();
        }
    }

    /**
     * Evicts everything in the instance registry that has expired, if expiry is enabled.
     *
     * @see com.netflix.eureka.lease.LeaseManager#evict()
     *
     * expectedNumberOfRenewsPerMin = 当前注册的应用实例数 x 2
     * numberOfRenewsPerMinThreshold = expectedNumberOfRenewsPerMin * 续租百分比( eureka.renewalPercentThreshold )
     */
    @Override
    public void evict() {
        evict(0l);
    }

    /**
     * 清理过期节点
     * @param additionalLeaseMs
     *
     * 当每分钟心跳次数( renewsLastMin ) 小于 numberOfRenewsPerMinThreshold 时，并且开启自动保护模式开关( eureka.enableSelfPreservation = true ) 时，
     * 触发自动保护机制，不再自动过期租约
     */
    public void evict(long additionalLeaseMs) {
        logger.debug("Running the evict task");

        /**
         * 是否过期删除
         */
        if (!isLeaseExpirationEnabled()) {
            logger.debug("DS: lease expiration is currently disabled.");
            return;
        }

        // We collect first all expired items, to evict them in random order. For large eviction sets,
        // if we do not that, we might wipe out whole apps before self preservation kicks in. By randomizing it,
        // the impact should be evenly distributed across all applications.
        List<Lease<InstanceInfo>> expiredLeases = new ArrayList<>();
        for (Entry<String, Map<String, Lease<InstanceInfo>>> groupEntry : registry.entrySet()) {
            Map<String, Lease<InstanceInfo>> leaseMap = groupEntry.getValue();
            if (leaseMap != null) {
                for (Entry<String, Lease<InstanceInfo>> leaseEntry : leaseMap.entrySet()) {
                    Lease<InstanceInfo> lease = leaseEntry.getValue();

                    // 判断是否过期，此处为重点，里面有判断实例过期的依据
                    if (lease.isExpired(additionalLeaseMs) && lease.getHolder() != null) {
                        expiredLeases.add(lease);
                    }
                }
            }
        }

        // // 计算 最大允许清理租约数量
        // To compensate for GC pauses or drifting local time, we need to use current registry size as a base for
        // triggering self-preservation. Without that we would wipe out full registry.

        // 获取注册的实例数量
        int registrySize = (int) getLocalRegistrySize();

        // serverConfig.getRenewalPercentThreshold() 为0.85 ， 主要是为了避免开启自动保护机制。 所以会逐步过期
        int registrySizeThreshold = (int) (registrySize * serverConfig.getRenewalPercentThreshold());

        //    // 可以过期的数量
        int evictionLimit = registrySize - registrySizeThreshold;

        // 取最小值，在过期数量和可以过期的数量中间取最小值。
        int toEvict = Math.min(expiredLeases.size(), evictionLimit);
        if (toEvict > 0) {
            logger.info("Evicting {} items (expired={}, evictionLimit={})", toEvict, expiredLeases.size(), evictionLimit);

            // 逐个过期
            Random random = new Random(System.currentTimeMillis());
            for (int i = 0; i < toEvict; i++) {
                // Pick a random item (Knuth shuffle algorithm)
                int next = i + random.nextInt(expiredLeases.size() - i);
                Collections.swap(expiredLeases, i, next);
                Lease<InstanceInfo> lease = expiredLeases.get(i);

                String appName = lease.getHolder().getAppName();
                String id = lease.getHolder().getId();
                EXPIRED.increment();
                logger.warn("DS: Registry: expired lease for {}/{}", appName, id);
                internalCancel(appName, id, false);
            }


            /**
             * Eureka-Server 关闭自我保护机制
             * enewalPercentThreshold = 0.85 默认配置，结果会是分批逐步过期
             *
             * 如果想关闭分批逐步过期，设置 renewalPercentThreshold = 0
             *
             * // 第一轮执行开始
             * int registrySize = 20;
             * int registrySizeThreshold = (int) (20 * 0.85) = 17;
             * int evictionLimit = 20 - 17 = 3;
             * int toEvict = Math.min(10, 3) = 3;
             * // 第一轮执行结束，剩余 17 个租约，其中有 7 个租约过期。
             *
             * // 第二轮执行开始
             * int registrySize = 17;
             * int registrySizeThreshold = (int) (17 * 0.85) = 14;
             * int evictionLimit = 17 - 14 = 3;
             * int toEvict = Math.min(7, 3) = 3;
             * // 第二轮执行结束，剩余 14 个租约，其中有 4 个租约过期。
             *
             * // 第三轮执行开始
             * int registrySize = 14;
             * int registrySizeThreshold = (int) (14 * 0.85) = 11;
             * int evictionLimit = 14 - 11 = 3;
             * int toEvict = Math.min(4, 3) = 3;
             * // 第三轮执行结束，剩余 11 个租约，其中有 1 个租约过期。
             *
             * // 第四轮执行开始
             * int registrySize = 11;
             * int registrySizeThreshold = (int) (11 * 0.85) = 9;
             * int evictionLimit = 11 - 9 = 2;
             * int toEvict = Math.min(1, 2) = 1;
             * // 第四轮执行结束，剩余 10 个租约，其中有 0 个租约过期。结束。
             *
             */
        }
    }


    /**
     * Returns the given app that is in this instance only, falling back to other regions transparently only
     * if specified in this client configuration.
     *
     * @param appName the application name of the application
     * @return the application
     *
     * @see com.netflix.discovery.shared.LookupService#getApplication(java.lang.String)
     */
    @Override
    public Application getApplication(String appName) {
        boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();
        return this.getApplication(appName, !disableTransparentFallback);
    }

    /**
     * Get application information.
     *
     * @param appName The name of the application
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link URL} by this property
     *                            {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the application
     */
    @Override
    public Application getApplication(String appName, boolean includeRemoteRegion) {
        Application app = null;

        Map<String, Lease<InstanceInfo>> leaseMap = registry.get(appName);

        if (leaseMap != null && leaseMap.size() > 0) {
            for (Entry<String, Lease<InstanceInfo>> entry : leaseMap.entrySet()) {
                if (app == null) {
                    app = new Application(appName);
                }
                app.addInstance(decorateInstanceInfo(entry.getValue()));
            }
        } else if (includeRemoteRegion) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Application application = remoteRegistry.getApplication(appName);
                if (application != null) {
                    return application;
                }
            }
        }
        return app;
    }

    /**
     * Get all applications in this instance registry, falling back to other regions if allowed in the Eureka config.
     *
     * @return the list of all known applications
     *
     * @see com.netflix.discovery.shared.LookupService#getApplications()
     */
    public Applications getApplications() {

        /**
         * disableTransparentFallbackToOtherRegion 是否禁用本地读取不到注册信息，从远程 Eureka-Server 读取。
         * 本地没有就会从远程读取  默认false
         */
        boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();

        if (disableTransparentFallback) {
            return getApplicationsFromLocalRegionOnly();
        } else {
            /**
             * 从远程节点找
             */
            return getApplicationsFromAllRemoteRegions();  // Behavior of falling back to remote region can be disabled.
        }
    }

    /**
     * Returns applications including instances from all remote regions. <br/>
     * Same as calling {@link #getApplicationsFromMultipleRegions(String[])} with a <code>null</code> argument.
     */
    public Applications getApplicationsFromAllRemoteRegions() {
        return getApplicationsFromMultipleRegions(allKnownRemoteRegions);
    }

    /**
     * Returns applications including instances from local region only. <br/>
     * Same as calling {@link #getApplicationsFromMultipleRegions(String[])} with an empty array.
     */

    /**
     * 返回本地区域的实例
     * @return
     */
    @Override
    public Applications getApplicationsFromLocalRegionOnly() {
        return getApplicationsFromMultipleRegions(EMPTY_STR_ARRAY);
    }

    /**
     * This method will return applications with instances from all passed remote regions as well as the current region.
     * Thus, this gives a union view of instances from multiple regions. <br/>
     * The application instances for which this union will be done can be restricted to the names returned by
     * {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} for every region. In case, there is no whitelist
     * defined for a region, this method will also look for a global whitelist by passing <code>null</code> to the
     * method {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} <br/>
     * If you are not selectively requesting for a remote region, use {@link #getApplicationsFromAllRemoteRegions()}
     * or {@link #getApplicationsFromLocalRegionOnly()}
     *
     * @param remoteRegions The remote regions for which the instances are to be queried. The instances may be limited
     *                      by a whitelist as explained above. If <code>null</code> or empty no remote regions are
     *                      included.
     *
     * @return The applications with instances from the passed remote regions as well as local region. The instances
     * from remote regions can be only for certain whitelisted apps as explained above.
     */


    //所有传递的远程区域以及当前区域的实例的应用程序

    /**
     * 只依赖 registry 共享变量，不存在应用实例的状态一致的困扰，所以不使用锁
     * @param remoteRegions
     * @return
     */
    public Applications getApplicationsFromMultipleRegions(String[] remoteRegions) {

        boolean includeRemoteRegion = null != remoteRegions && remoteRegions.length != 0;

        logger.debug("Fetching applications registry with remote regions: {}, Regions argument {}",
                includeRemoteRegion, remoteRegions);

        if (includeRemoteRegion) {
            GET_ALL_WITH_REMOTE_REGIONS_CACHE_MISS.increment();
        } else {
            GET_ALL_CACHE_MISS.increment();
        }

        // 获得获得注册的应用集合
        Applications apps = new Applications();
        apps.setVersion(1L);
        for (Entry<String, Map<String, Lease<InstanceInfo>>> entry : registry.entrySet()) {
            Application app = null;

            if (entry.getValue() != null) {
                for (Entry<String, Lease<InstanceInfo>> stringLeaseEntry : entry.getValue().entrySet()) {
                    Lease<InstanceInfo> lease = stringLeaseEntry.getValue();
                    if (app == null) {
                        app = new Application(lease.getHolder().getAppName());
                    }
                    app.addInstance(decorateInstanceInfo(lease));
                }
            }
            if (app != null) {
                apps.addApplication(app);
            }
        }

        /**
         * 是否包含远程Region 的 注册信息
         */
        if (includeRemoteRegion) {
            for (String remoteRegion : remoteRegions) {
                RemoteRegionRegistry remoteRegistry = regionNameVSRemoteRegistry.get(remoteRegion);
                if (null != remoteRegistry) {
                    /**
                     * 获取远程区域下的 节点 Zoo
                     */
                    Applications remoteApps = remoteRegistry.getApplications();
                    for (Application application : remoteApps.getRegisteredApplications()) {
                        if (shouldFetchFromRemoteRegistry(application.getName(), remoteRegion)) {
                            logger.info("Application {}  fetched from the remote region {}",
                                    application.getName(), remoteRegion);

                            Application appInstanceTillNow = apps.getRegisteredApplications(application.getName());
                            if (appInstanceTillNow == null) {
                                appInstanceTillNow = new Application(application.getName());
                                apps.addApplication(appInstanceTillNow);
                            }
                            for (InstanceInfo instanceInfo : application.getInstances()) {
                                appInstanceTillNow.addInstance(instanceInfo);
                            }
                        } else {
                            logger.debug("Application {} not fetched from the remote region {} as there exists a "
                                            + "whitelist and this app is not in the whitelist.",
                                    application.getName(), remoteRegion);
                        }
                    }
                } else {
                    logger.warn("No remote registry available for the remote region {}", remoteRegion);
                }
            }
        }

        /**
         * 计算应用集合 hashcode 。该变量用于校验增量获取的注册信息和 Eureka-Server 全量的注册信息是否一致( 完整
         */
        apps.setAppsHashCode(apps.getReconcileHashCode());
        return apps;
    }

    private boolean shouldFetchFromRemoteRegistry(String appName, String remoteRegion) {
        Set<String> whiteList = serverConfig.getRemoteRegionAppWhitelist(remoteRegion);
        if (null == whiteList) {
            whiteList = serverConfig.getRemoteRegionAppWhitelist(null); // see global whitelist.
        }
        return null == whiteList || whiteList.contains(appName);
    }

    /**
     * Get the registry information about all {@link Applications}.
     *
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link URL} by this property
     *                            {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return applications
     *
     * @deprecated Use {@link #getApplicationsFromMultipleRegions(String[])} instead. This method has a flawed behavior
     * of transparently falling back to a remote region if no instances for an app is available locally. The new
     * behavior is to explicitly specify if you need a remote region.
     */
    @Deprecated
    public Applications getApplications(boolean includeRemoteRegion) {
        GET_ALL_CACHE_MISS.increment();
        Applications apps = new Applications();
        apps.setVersion(1L);
        for (Entry<String, Map<String, Lease<InstanceInfo>>> entry : registry.entrySet()) {
            Application app = null;

            if (entry.getValue() != null) {
                for (Entry<String, Lease<InstanceInfo>> stringLeaseEntry : entry.getValue().entrySet()) {

                    Lease<InstanceInfo> lease = stringLeaseEntry.getValue();

                    if (app == null) {
                        app = new Application(lease.getHolder().getAppName());
                    }

                    app.addInstance(decorateInstanceInfo(lease));
                }
            }
            if (app != null) {
                apps.addApplication(app);
            }
        }
        if (includeRemoteRegion) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Applications applications = remoteRegistry.getApplications();
                for (Application application : applications
                        .getRegisteredApplications()) {
                    Application appInLocalRegistry = apps
                            .getRegisteredApplications(application.getName());
                    if (appInLocalRegistry == null) {
                        apps.addApplication(application);
                    }
                }
            }
        }
        apps.setAppsHashCode(apps.getReconcileHashCode());
        return apps;
    }

    /**
     * Get the registry information about the delta changes. The deltas are
     * cached for a window specified by
     * {@link EurekaServerConfig#getRetentionTimeInMSInDeltaQueue()}. Subsequent
     * requests for delta information may return the same information and client
     * must make sure this does not adversely affect them.
     *
     * @return all application deltas.
     * @deprecated use {@link #getApplicationDeltasFromMultipleRegions(String[])} instead. This method has a
     * flawed behavior of transparently falling back to a remote region if no instances for an app is available locally.
     * The new behavior is to explicitly specify if you need a remote region.
     */
    @Deprecated
    public Applications getApplicationDeltas() {

        // 添加 增量获取次数 到 监控
        GET_ALL_CACHE_MISS_DELTA.increment();
        // 初始化 变化的应用集合
        Applications apps = new Applications();
        apps.setVersion(responseCache.getVersionDelta().get());
        Map<String, Application> applicationInstancesMap = new HashMap<String, Application>();
        try {
            // 获取写锁
            write.lock();

            // 获取 最近租约变更记录队列
            Iterator<RecentlyChangedItem> iter = this.recentlyChangedQueue.iterator();
            logger.debug("The number of elements in the delta queue is : {}",
                    this.recentlyChangedQueue.size());

            // 拼装 变化的应用集合
            while (iter.hasNext()) {
                Lease<InstanceInfo> lease = iter.next().getLeaseInfo();
                InstanceInfo instanceInfo = lease.getHolder();
                logger.debug(
                        "The instance id {} is found with status {} and actiontype {}",
                        instanceInfo.getId(), instanceInfo.getStatus().name(), instanceInfo.getActionType().name());
                Application app = applicationInstancesMap.get(instanceInfo
                        .getAppName());
                if (app == null) {
                    app = new Application(instanceInfo.getAppName());
                    applicationInstancesMap.put(instanceInfo.getAppName(), app);
                    apps.addApplication(app);
                }
                app.addInstance(new InstanceInfo(decorateInstanceInfo(lease)));
            }

            boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();

            if (!disableTransparentFallback) {
                Applications allAppsInLocalRegion = getApplications(false);

                for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                    Applications applications = remoteRegistry.getApplicationDeltas();
                    for (Application application : applications.getRegisteredApplications()) {
                        Application appInLocalRegistry =
                                allAppsInLocalRegion.getRegisteredApplications(application.getName());
                        if (appInLocalRegistry == null) {
                            apps.addApplication(application);
                        }
                    }
                }
            }
            // 获取全量应用集合，通过它计算一致性哈希值
            Applications allApps = getApplications(!disableTransparentFallback);

            //这个全量应用集合的哈希值，Eureka-Client 获取到增量应用集合并合并后 进行对比
            apps.setAppsHashCode(allApps.getReconcileHashCode());
            return apps;
        } finally {
            write.unlock();
        }
    }

    /**
     * Gets the application delta also including instances from the passed remote regions, with the instances from the
     * local region. <br/>
     *
     * The remote regions from where the instances will be chosen can further be restricted if this application does not
     * appear in the whitelist specified for the region as returned by
     * {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} for a region. In case, there is no whitelist
     * defined for a region, this method will also look for a global whitelist by passing <code>null</code> to the
     * method {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} <br/>
     *
     * @param remoteRegions The remote regions for which the instances are to be queried. The instances may be limited
     *                      by a whitelist as explained above. If <code>null</code> all remote regions are included.
     *                      If empty list then no remote region is included.
     *
     * @return The delta with instances from the passed remote regions as well as local region. The instances
     * from remote regions can be further be restricted as explained above. <code>null</code> if the application does
     * not exist locally or in remote regions.
     */

    /**
     * 获取Applications
     * 需要保证 recentlyChangedQueue 和 registry 共享变量的应用实例的状态一致
     * 不然返回的增量应用实例集合的状态是不准确的
     *
     * a. 全部 synchronized
     * b. #getApplicationDeltasFromMultipleRegions(...) 使用读锁，前六个方法使用写锁
     * c. #getApplicationDeltasFromMultipleRegions(...) 使用写锁，前六个方法使用读锁
     *
     * Eureka 选择了方案c，原因如下：
     *
     * a. 性能太差
     * b. 前六个方法使用写锁，势必冲突太大，虽然读肯定比写多。
     * c. #getApplicationDeltasFromMultipleRegions(...) 使用写锁，配合 ResponseCache ，
     * 即减少了写锁使用的频率，每次缓存过期才使用，又避免了前六个方法因为方案b中的写锁导致互斥
     *
     *
     * @param remoteRegions
     * @return
     */
    public Applications getApplicationDeltasFromMultipleRegions(String[] remoteRegions) {
        if (null == remoteRegions) {
            remoteRegions = allKnownRemoteRegions; // null means all remote regions.
        }

        boolean includeRemoteRegion = remoteRegions.length != 0;

        if (includeRemoteRegion) {
            GET_ALL_WITH_REMOTE_REGIONS_CACHE_MISS_DELTA.increment();
        } else {
            GET_ALL_CACHE_MISS_DELTA.increment();
        }

        Applications apps = new Applications();
        apps.setVersion(responseCache.getVersionDeltaWithRegions().get());
        Map<String, Application> applicationInstancesMap = new HashMap<String, Application>();
        try {
            write.lock();

            /**
             * 锁住增量集合
             */
            Iterator<RecentlyChangedItem> iter = this.recentlyChangedQueue.iterator();
            logger.debug("The number of elements in the delta queue is :{}", this.recentlyChangedQueue.size());
            while (iter.hasNext()) {
                Lease<InstanceInfo> lease = iter.next().getLeaseInfo();
                InstanceInfo instanceInfo = lease.getHolder();
                logger.debug("The instance id {} is found with status {} and actiontype {}",
                        instanceInfo.getId(), instanceInfo.getStatus().name(), instanceInfo.getActionType().name());
                Application app = applicationInstancesMap.get(instanceInfo.getAppName());
                if (app == null) {
                    app = new Application(instanceInfo.getAppName());
                    applicationInstancesMap.put(instanceInfo.getAppName(), app);
                    apps.addApplication(app);
                }
                app.addInstance(new InstanceInfo(decorateInstanceInfo(lease)));
            }

            if (includeRemoteRegion) {
                for (String remoteRegion : remoteRegions) {
                    RemoteRegionRegistry remoteRegistry = regionNameVSRemoteRegistry.get(remoteRegion);
                    if (null != remoteRegistry) {
                        Applications remoteAppsDelta = remoteRegistry.getApplicationDeltas();
                        if (null != remoteAppsDelta) {
                            for (Application application : remoteAppsDelta.getRegisteredApplications()) {
                                if (shouldFetchFromRemoteRegistry(application.getName(), remoteRegion)) {
                                    Application appInstanceTillNow =
                                            apps.getRegisteredApplications(application.getName());
                                    if (appInstanceTillNow == null) {
                                        appInstanceTillNow = new Application(application.getName());
                                        apps.addApplication(appInstanceTillNow);
                                    }
                                    for (InstanceInfo instanceInfo : application.getInstances()) {
                                        appInstanceTillNow.addInstance(new InstanceInfo(instanceInfo));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Applications allApps = getApplicationsFromMultipleRegions(remoteRegions);
            apps.setAppsHashCode(allApps.getReconcileHashCode());
            return apps;
        } finally {
            write.unlock();
        }
    }

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName the application name for which the information is requested.
     * @param id the unique identifier of the instance.
     * @return the information about the instance.
     */
    @Override
    public InstanceInfo getInstanceByAppAndId(String appName, String id) {
        return this.getInstanceByAppAndId(appName, id, true);
    }

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName the application name for which the information is requested.
     * @param id the unique identifier of the instance.
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link URL} by this property
     *                             {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the information about the instance.
     */
    @Override
    public InstanceInfo getInstanceByAppAndId(String appName, String id, boolean includeRemoteRegions) {
        Map<String, Lease<InstanceInfo>> leaseMap = registry.get(appName);
        Lease<InstanceInfo> lease = null;
        if (leaseMap != null) {
            lease = leaseMap.get(id);
        }
        if (lease != null
                && (!isLeaseExpirationEnabled() || !lease.isExpired())) {
            return decorateInstanceInfo(lease);
        } else if (includeRemoteRegions) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Application application = remoteRegistry.getApplication(appName);
                if (application != null) {
                    return application.getByInstanceId(id);
                }
            }
        }
        return null;
    }

    /**
     * @deprecated Try {@link #getInstanceByAppAndId(String, String)} instead.
     *
     * Get all instances by ID, including automatically asking other regions if the ID is unknown.
     *
     * @see com.netflix.discovery.shared.LookupService#getInstancesById(String)
     */
    @Deprecated
    public List<InstanceInfo> getInstancesById(String id) {
        return this.getInstancesById(id, true);
    }

    /**
     * @deprecated Try {@link #getInstanceByAppAndId(String, String, boolean)} instead.
     *
     * Get the list of instances by its unique id.
     *
     * @param id the unique id of the instance
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link URL} by this property
     *                             {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return list of InstanceInfo objects.
     */
    @Deprecated
    public List<InstanceInfo> getInstancesById(String id, boolean includeRemoteRegions) {
        List<InstanceInfo> list = new ArrayList<InstanceInfo>();

        for (Iterator<Entry<String, Map<String, Lease<InstanceInfo>>>> iter =
                     registry.entrySet().iterator(); iter.hasNext(); ) {

            Map<String, Lease<InstanceInfo>> leaseMap = iter.next().getValue();
            if (leaseMap != null) {
                Lease<InstanceInfo> lease = leaseMap.get(id);

                if (lease == null || (isLeaseExpirationEnabled() && lease.isExpired())) {
                    continue;
                }

                if (list == Collections.EMPTY_LIST) {
                    list = new ArrayList<InstanceInfo>();
                }
                list.add(decorateInstanceInfo(lease));
            }
        }
        if (list.isEmpty() && includeRemoteRegions) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                for (Application application : remoteRegistry.getApplications()
                        .getRegisteredApplications()) {
                    InstanceInfo instanceInfo = application.getByInstanceId(id);
                    if (instanceInfo != null) {
                        list.add(instanceInfo);
                        return list;
                    }
                }
            }
        }
        return list;
    }

    private InstanceInfo decorateInstanceInfo(Lease<InstanceInfo> lease) {
        InstanceInfo info = lease.getHolder();

        // client app settings
        int renewalInterval = LeaseInfo.DEFAULT_LEASE_RENEWAL_INTERVAL;
        int leaseDuration = LeaseInfo.DEFAULT_LEASE_DURATION;

        // TODO: clean this up
        if (info.getLeaseInfo() != null) {
            renewalInterval = info.getLeaseInfo().getRenewalIntervalInSecs();
            leaseDuration = info.getLeaseInfo().getDurationInSecs();
        }

        info.setLeaseInfo(LeaseInfo.Builder.newBuilder()
                .setRegistrationTimestamp(lease.getRegistrationTimestamp())
                .setRenewalTimestamp(lease.getLastRenewalTimestamp())
                .setServiceUpTimestamp(lease.getServiceUpTimestamp())
                .setRenewalIntervalInSecs(renewalInterval)
                .setDurationInSecs(leaseDuration)
                .setEvictionTimestamp(lease.getEvictionTimestamp()).build());

        info.setIsCoordinatingDiscoveryServer();
        return info;
    }

    /**
     * Servo route; do not call.
     *
     * @return servo data
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfRenewsInLastMin",
            description = "Number of total heartbeats received in the last minute", type = DataSourceType.GAUGE)
    @Override
    public long getNumOfRenewsInLastMin() {
        return renewsLastMin.getCount();
    }


    /**
     * Gets the threshold for the renewals per minute.
     *
     * @return the integer representing the threshold for the renewals per
     *         minute.
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfRenewsPerMinThreshold", type = DataSourceType.GAUGE)
    @Override
    public int getNumOfRenewsPerMinThreshold() {
        return numberOfRenewsPerMinThreshold;
    }

    /**
     * Get the N instances that are most recently registered.
     *
     * @return
     */
    @Override
    public List<Pair<Long, String>> getLastNRegisteredInstances() {
        List<Pair<Long, String>> list = new ArrayList<Pair<Long, String>>();

        synchronized (recentRegisteredQueue) {
            for (Pair<Long, String> aRecentRegisteredQueue : recentRegisteredQueue) {
                list.add(aRecentRegisteredQueue);
            }
        }
        Collections.reverse(list);
        return list;
    }

    /**
     * Get the N instances that have most recently canceled.
     *
     * @return
     */
    @Override
    public List<Pair<Long, String>> getLastNCanceledInstances() {
        List<Pair<Long, String>> list = new ArrayList<Pair<Long, String>>();
        synchronized (recentCanceledQueue) {
            for (Pair<Long, String> aRecentCanceledQueue : recentCanceledQueue) {
                list.add(aRecentCanceledQueue);
            }
        }
        Collections.reverse(list);
        return list;
    }

    /**
     *  // 设置 响应缓存 过期
     * @param appName
     * @param vipAddress
     * @param secureVipAddress
     */
    private void invalidateCache(String appName, @Nullable String vipAddress, @Nullable String secureVipAddress) {
        // invalidate cache
        responseCache.invalidate(appName, vipAddress, secureVipAddress);
    }


    /**
     * 计算公式
     * expectedNumberOfRenewsPerMin = 当前注册的应用实例数 x 2
     * numberOfRenewsPerMinThreshold = expectedNumberOfRenewsPerMin * 续租百分比( eureka.renewalPercentThreshold )
     */
    protected void updateRenewsPerMinThreshold() {
        this.numberOfRenewsPerMinThreshold = (int) (this.expectedNumberOfClientsSendingRenews
                           // 默认值30
                * (60.0 / serverConfig.getExpectedClientRenewalIntervalSeconds())
                * serverConfig.getRenewalPercentThreshold());
    }

    private static final class RecentlyChangedItem {

        /**
         * 最后更新时间戳
         */
        private long lastUpdateTime;
        /**
         * 租约
         */
        private Lease<InstanceInfo> leaseInfo;

        public RecentlyChangedItem(Lease<InstanceInfo> lease) {
            this.leaseInfo = lease;
            lastUpdateTime = System.currentTimeMillis();
        }

        public long getLastUpdateTime() {
            return this.lastUpdateTime;
        }

        public Lease<InstanceInfo> getLeaseInfo() {
            return this.leaseInfo;
        }
    }

    protected void postInit() {
        renewsLastMin.start();
        if (evictionTaskRef.get() != null) {
            evictionTaskRef.get().cancel();
        }
        evictionTaskRef.set(new EvictionTask());

        /**
         * 定时执行任务 清理失效节点
         * 默认60秒
         */
        evictionTimer.schedule(evictionTaskRef.get(),
                serverConfig.getEvictionIntervalTimerInMs(),
                serverConfig.getEvictionIntervalTimerInMs());
    }

    /**
     * Perform all cleanup and shutdown operations.
     */
    @Override
    public void shutdown() {
        deltaRetentionTimer.cancel();
        evictionTimer.cancel();
        renewsLastMin.stop();
    }

    @com.netflix.servo.annotations.Monitor(name = "numOfElementsinInstanceCache", description = "Number of overrides in the instance Cache", type = DataSourceType.GAUGE)
    public long getNumberofElementsininstanceCache() {
        return overriddenInstanceStatusMap.size();
    }

    /* visible for testing */ class EvictionTask extends TimerTask {

        private final AtomicLong lastExecutionNanosRef = new AtomicLong(0l);

        /**
         * 过期检测
         */
        @Override
        public void run() {
            try {

                // 获取 补偿时间毫秒数
                /**
                 * 计算公式 = 当前时间 - 最后任务执行时间 - 任务执行频率 60秒
                 */
                long compensationTimeMs = getCompensationTimeMs();
                logger.info("Running the evict task with compensationTime {}ms", compensationTimeMs);

                //清理过期
                evict(compensationTimeMs);
            } catch (Throwable e) {
                logger.error("Could not run the evict task", e);
            }
        }

        /**
         * compute a compensation time defined as the actual time this task was executed since the prev iteration,
         * vs the configured amount of time for execution. This is useful for cases where changes in time (due to
         * clock skew or gc for example) causes the actual eviction task to execute later than the desired time
         * according to the configured cycle.
         */

        /**
         * 最后任务的执行时间
         * @return
         */
        long getCompensationTimeMs() {

            long currNanos = getCurrentTimeNano();
            long lastNanos = lastExecutionNanosRef.getAndSet(currNanos);
            if (lastNanos == 0l) {
                return 0l;
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(currNanos - lastNanos);
            long compensationTime = elapsedMs - serverConfig.getEvictionIntervalTimerInMs();
            return compensationTime <= 0l ? 0l : compensationTime;
        }

        long getCurrentTimeNano() {  // for testing
            return System.nanoTime();
        }

    }

    private class CircularQueue<E> extends ConcurrentLinkedQueue<E> {
        private int size = 0;

        public CircularQueue(int size) {
            this.size = size;
        }

        @Override
        public boolean add(E e) {
            this.makeSpaceIfNotAvailable();
            return super.add(e);

        }

        private void makeSpaceIfNotAvailable() {
            if (this.size() == size) {
                this.remove();
            }
        }

        public boolean offer(E e) {
            this.makeSpaceIfNotAvailable();
            return super.offer(e);
        }
    }

    /**
     * @return The rule that will process the instance status override.
     * 实例状态规则的复制
     */
    protected abstract InstanceStatusOverrideRule getInstanceInfoOverrideRule();


    /**
     * 根据应用实例状态覆盖规则( InstanceStatusOverrideRule )进行计算最终应用实例的状态
     * @param r
     * @param existingLease
     * @param isReplication
     * @return
     */
    protected InstanceInfo.InstanceStatus getOverriddenInstanceStatus(InstanceInfo r,
                                                                    Lease<InstanceInfo> existingLease,
                                                                    boolean isReplication) {
        InstanceStatusOverrideRule rule = getInstanceInfoOverrideRule();
        logger.debug("Processing override status using rule: {}", rule);
        return rule.apply(r, existingLease, isReplication).status();
    }

    private TimerTask getDeltaRetentionTask() {
        return new TimerTask() {

            @Override
            public void run() {
                Iterator<RecentlyChangedItem> it = recentlyChangedQueue.iterator();
                while (it.hasNext()) {

                    //上一次更新时间小于 租约变更记录过期时长  移除
                    if (it.next().getLastUpdateTime() <
                            //eureka.retentionTimeInMSInDeltaQueue，租约变更记录过期时长，单位：毫秒。默认值 ： 3 60 1000 毫秒
                            System.currentTimeMillis() - serverConfig.getRetentionTimeInMSInDeltaQueue()) {
                        it.remove();
                    } else {
                        break;
                    }
                }
            }

        };
    }
}
