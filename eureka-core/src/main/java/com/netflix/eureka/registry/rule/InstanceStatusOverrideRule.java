package com.netflix.eureka.registry.rule;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.eureka.lease.Lease;
import com.netflix.eureka.registry.AbstractInstanceRegistry;

/**
 * A single rule that if matched it returns an instance status.
 * The idea is to use an ordered list of such rules and pick the first result that matches.
 *
 * It is designed to be used by
 * {@link AbstractInstanceRegistry#getOverriddenInstanceStatus(InstanceInfo, Lease, boolean)}
 *
 * Created by Nikos Michalakis on 7/13/16.
 */

/**
 *
 *单个规则，如果匹配则返回实例状态。
 *这个想法是使用这些规则的有序列表，并选择匹配的第一个结果。
 */
public interface InstanceStatusOverrideRule {

    /**
     * Match this rule.
     *
     * @param instanceInfo The instance info whose status we care about.
     * @param existingLease Does the instance have an existing lease already? If so let's consider that.
     * @param isReplication When overriding consider if we are under a replication mode from other servers.
     * @return A result with whether we matched and what we propose the status to be overriden to.
     */

    /**
     * /**
     *       *符合此规则。
     *      *
     *       * @param instanceInfo我们关心其状态的实例信息。
     *       * @param existingLease实例是否已有现有租约？ 如果是这样，我们考虑一下。
     *       * @param isReplication当覆盖时考虑我们是否处于来自其他服务器的复制模式。
     *       * @return一个结果，我们是否匹配以及我们建议要覆盖的状态。
     *      */
    StatusOverrideResult apply(final InstanceInfo instanceInfo,
                               final Lease<InstanceInfo> existingLease,
                               boolean isReplication);

}
