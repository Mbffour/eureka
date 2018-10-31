package com.netflix.text;

public class InitTest {

    /**
     * 1 创建 EurekaInstanceConfig对象
     * 2 使用 EurekaInstanceConfig对象 创建 InstanceInfo对象
     * 3 使用 EurekaInstanceConfig对象 + InstanceInfo对象 创建 ApplicationInfoManager对象
     * 4 创建 EurekaClientConfig对象
     * 4  DefaultEurekaClientConfig   -》 EurekaTransportConfig
     * 5 使用 ApplicationInfoManager对象 + EurekaClientConfig对象 创建 EurekaClient对象
     *
     * EurekaInstanceConfig，重在应用实例，例如，应用名、应用的端口等等。
     * 此处应用指的是，Application Consumer 和 Application Provider。
     *
     *
     * EurekaClientConfig，重在 Eureka-Client，例如， 连接的 Eureka-Server 的地址、
     * 获取服务提供者列表的频率、注册自身为服务提供者的频率等等。
     *
     */


    /**
     * 1 EurekaInstanceConfig
     * 2 AbstractInstanceConfig
     * 3 PropertiesInstanceConfig
     * 4 MyDataCenterInstanceConfig/CloudInstanceConfig(基于亚马逊 AWS)
     *
     */


    /**
     * EurekaConfigBasedInstanceInfoProvider 基于 EurekaInstanceConfig 创建 InstanceInfo 的工厂
     * 构建应用实例信息 租约信息 ip host （AWS方式）等
     *
     *
     * ApplicationInfoManager#setInstanceStatus(...) 方法改变应用实例状态
     */


    /**
     * 使用 DNS 获取 Eureka-Server URL 相关
     * #shouldUseDnsForFetchingServiceUrls() ：是否使用 DNS 方式获取 Eureka-Server URL 地址。
     * #getEurekaServerDNSName() ：Eureka-Server 的 DNS 名。
     * #getEurekaServerPort() ：Eureka-Server 的端口。
     * #getEurekaServerURLContext() ：Eureka-Server 的 URL Context 。
     * #getEurekaServiceUrlPollIntervalSeconds() ：轮询获取 Eureka-Server 地址变更频率，单位：秒。默认：300 秒。
     * #shouldPreferSameZoneEureka() ：优先使用相同区( zone )的 Eureka-Server。
     *
     * 直接配合 Eureka-Server URL 相关
     * #getEurekaServerServiceUrls() ： Eureka-Server 的 URL 集合。
     * 发现：从 Eureka-Server 获取注册信息相关
     * #shouldFetchRegistry() ：是否从 Eureka-Server 拉取注册信息。
     * #getRegistryFetchIntervalSeconds() ：从 Eureka-Server 拉取注册信息频率，单位：秒。默认：30 秒。
     * #shouldFilterOnlyUpInstances() ：是否过滤，只获取状态为开启( Up )的应用实例集合。
     * #fetchRegistryForRemoteRegions() ：TODO[0009]：RemoteRegionRegistry
     * #getCacheRefreshExecutorThreadPoolSize() ：注册信息缓存刷新线程池大小。
     * #getCacheRefreshExecutorExponentialBackOffBound() ：注册信息缓存刷新执行超时后的延迟重试的时间。
     * #getRegistryRefreshSingleVipAddress() ：只获得一个 vipAddress 对应的应用实例们的注册信息。
     * 实现逻辑和 《Eureka 源码解析 —— 应用实例注册发现 （六）之全量获取》
     * 本系列暂时写对它的源码解析，感兴趣的同学可以看 com.netflix.discovery.shared.transport.EurekaHttpClient#getVip(String, String...) 和 com.netflix.eureka.resources.AbstractVIPResource 。
     *
     *
     *
     * 注册：向 Eureka-Server 注册自身服务
     * #shouldRegisterWithEureka() ：是否向 Eureka-Server 注册自身服务。
     * #shouldUnregisterOnShutdown() ：是否向 Eureka-Server 取消注册自身服务，当进程关闭时。
     * #getInstanceInfoReplicationIntervalSeconds() ：向 Eureka-Server 同步应用实例信息变化频率，单位：秒。
     * #getInitialInstanceInfoReplicationIntervalSeconds() ：向 Eureka-Server 同步应用信息变化初始化延迟，单位：秒。
     * #getBackupRegistryImpl() ：获取备份注册中心实现类。当 Eureka-Client 启动时，无法从 Eureka-Server 读取注册信息（可能挂了），从备份注册中心读取注册信息。目前 Eureka-Client 未提供合适的实现。
     * #getHeartbeatExecutorThreadPoolSize() ：心跳执行线程池大小。
     * #getHeartbeatExecutorExponentialBackOffBound() ：心跳执行超时后的延迟重试的时间。
     */

}
