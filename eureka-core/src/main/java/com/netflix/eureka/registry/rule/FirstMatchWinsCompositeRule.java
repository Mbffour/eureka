package com.netflix.eureka.registry.rule;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.eureka.lease.Lease;

import java.util.ArrayList;
import java.util.List;

/**
 * This rule takes an ordered list of rules and returns the result of the first match or the
 * result of the {@link AlwaysMatchInstanceStatusRule}.
 *
 * Created by Nikos Michalakis on 7/13/16.
 */

/**

 此规则采用有序的规则列表并返回第一个匹配的结果或
 */
public class FirstMatchWinsCompositeRule implements InstanceStatusOverrideRule {


    /**
     * rules 属性，复合规则集合。在 PeerAwareInstanceRegistryImpl 里，我们可以看到该属性为 [ DownOrStartingRule , OverrideExistsRule , LeaseExistsRule ] 。
     * defaultRule 属性，默认规则，值为 AlwaysMatchInstanceStatusRule 。
     * #apply() 方法，优先使用复合规则( rules )，顺序匹配，直到匹配成功 。当未匹配成功，使用默认规则( defaultRule )
     */
    private final InstanceStatusOverrideRule[] rules;
    private final InstanceStatusOverrideRule defaultRule;
    private final String compositeRuleName;

    public FirstMatchWinsCompositeRule(InstanceStatusOverrideRule... rules) {
        this.rules = rules;
        this.defaultRule = new AlwaysMatchInstanceStatusRule();
        // Let's build up and "cache" the rule name to be used by toString();
        List<String> ruleNames = new ArrayList<>(rules.length+1);
        for (int i = 0; i < rules.length; ++i) {
            ruleNames.add(rules[i].toString());
        }
        ruleNames.add(defaultRule.toString());
        compositeRuleName = ruleNames.toString();
    }

    @Override
    public StatusOverrideResult apply(InstanceInfo instanceInfo,
                                      Lease<InstanceInfo> existingLease,
                                      boolean isReplication) {

        // 使用复合规则，顺序匹配，直到匹配成功
        for (int i = 0; i < this.rules.length; ++i) {
            StatusOverrideResult result = this.rules[i].apply(instanceInfo, existingLease, isReplication);
            if (result.matches()) {
                return result;
            }
        }

        // 使用默认规则
        return defaultRule.apply(instanceInfo, existingLease, isReplication);
    }

    @Override
    public String toString() {
        return this.compositeRuleName;
    }
}
