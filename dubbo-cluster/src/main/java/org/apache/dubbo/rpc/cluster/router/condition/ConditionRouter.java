/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.cluster.router.condition;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.dubbo.rpc.cluster.Constants.ADDRESS_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.FORCE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.PRIORITY_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RULE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RUNTIME_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY_PREFIX;
import static org.apache.dubbo.common.constants.CommonConstants.ENABLED_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHOD_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.HOST_KEY;

/**
 * ConditionRouter
 *
 */
public class ConditionRouter extends AbstractRouter {
    public static final String NAME = "condition";

    private static final Logger logger = LoggerFactory.getLogger(ConditionRouter.class);
    protected static final Pattern ROUTE_PATTERN = Pattern.compile("([&!=,]*)\\s*([^&!=,\\s]+)");
    protected Map<String, MatchPair> whenCondition;
    protected Map<String, MatchPair> thenCondition;

    private boolean enabled;

    public ConditionRouter(String rule, boolean force, boolean enabled) {
        this.force = force;
        this.enabled = enabled;
        this.init(rule);
    }

    public ConditionRouter(URL url) {
        this.url = url;
        this.priority = url.getParameter(PRIORITY_KEY, 0);
        this.force = url.getParameter(FORCE_KEY, false);
        this.enabled = url.getParameter(ENABLED_KEY, true);
        init(url.getParameterAndDecoded(RULE_KEY)); // 初始化条件路由
    }

    public void init(String rule) {
        try {
            if (rule == null || rule.trim().length() == 0) {
                throw new IllegalArgumentException("Illegal route rule!");
            }
            rule = rule.replace("consumer.", "").replace("provider.", "");
            int i = rule.indexOf("=>");
            // 解析whenRule
            String whenRule = i < 0 ? null : rule.substring(0, i).trim();
            // 解析thenRule
            String thenRule = i < 0 ? rule.trim() : rule.substring(i + 2).trim();
            // 将when表达式解析为Map<String, MatchPair>
            // 数据示例：key=host,value=MatchPair
            // MatchPair.matches={'192.168.1.3'}
            Map<String, MatchPair> when = StringUtils.isBlank(whenRule) || "true".equals(whenRule) ? new HashMap<String, MatchPair>() : parseRule(whenRule);
            // 将then表达式解析为Map<String, MatchPair>
            // 数据示例：key=host,value=MatchPair;MatchPair.matches={'192.168.1.3'}
            // key=host,value=MatchPair;MatchPair.matches={'20881'}
            Map<String, MatchPair> then = StringUtils.isBlank(thenRule) || "false".equals(thenRule) ? null : parseRule(thenRule);
            // NOTE: It should be determined on the business level whether the `When condition` can be empty or not.
            // 将解析后的when和then赋值给成员变量whenCondition和thenCondition
            this.whenCondition = when;
            this.thenCondition = then;
        } catch (ParseException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static Map<String, MatchPair> parseRule(String rule)
            throws ParseException {
        Map<String, MatchPair> condition = new HashMap<String, MatchPair>();
        if (StringUtils.isBlank(rule)) {
            return condition;
        }
        // Key-Value pair, stores both match and mismatch conditions
        MatchPair pair = null;
        // Multiple values
        Set<String> values = null;
        final Matcher matcher = ROUTE_PATTERN.matcher(rule);
        while (matcher.find()) { // Try to match one by one
            String separator = matcher.group(1);
            String content = matcher.group(2);
            // Start part of the condition expression.
            if (StringUtils.isEmpty(separator)) {
                pair = new MatchPair();
                condition.put(content, pair);
            }
            // The KV part of the condition expression
            else if ("&".equals(separator)) {
                if (condition.get(content) == null) {
                    pair = new MatchPair();
                    condition.put(content, pair);
                } else {
                    pair = condition.get(content);
                }
            }
            // The Value in the KV part.
            else if ("=".equals(separator)) {
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                values = pair.matches;
                values.add(content);
            }
            // The Value in the KV part.
            else if ("!=".equals(separator)) {
                if (pair == null) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }

                values = pair.mismatches;
                values.add(content);
            }
            // The Value in the KV part, if Value have more than one items.
            else if (",".equals(separator)) { // Should be separated by ','
                if (values == null || values.isEmpty()) {
                    throw new ParseException("Illegal route rule \""
                            + rule + "\", The error char '" + separator
                            + "' at index " + matcher.start() + " before \""
                            + content + "\".", matcher.start());
                }
                values.add(content);
            } else {
                throw new ParseException("Illegal route rule \"" + rule
                        + "\", The error char '" + separator + "' at index "
                        + matcher.start() + " before \"" + content + "\".", matcher.start());
            }
        }
        return condition;
    }

    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation)
            throws RpcException {
        if (!enabled) { // 如果当前条件路由未启用，直接返回原Invoker集合
            return invokers;
        }

        if (CollectionUtils.isEmpty(invokers)) { // 如果Invoker集合为空，直接返回原Invoker集合
            return invokers;
        }
        try {
            // 先匹配when
            // 当前服务消费者，如果不满足when条件，则表示不适用于当前条件路由，直接返回原Invoker集合
            if (!matchWhen(url, invocation)) {
                return invokers;
            }
            List<Invoker<T>> result = new ArrayList<Invoker<T>>();
            // 如果满足when，但then为空，则表示当前服务消费者在黑名单里，禁止调用任何服务提供者
            if (thenCondition == null) {
                logger.warn("The current consumer in the service blacklist. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey());
                return result;
            }
            for (Invoker<T> invoker : invokers) { // 遍历所有服务提供者Invoker，与then条件匹配
                if (matchThen(invoker.getUrl(), url)) { // 只有与then条件匹配的Invoker，才会添加到result中返回
                    result.add(invoker);
                }
            }
            if (!result.isEmpty()) { // 如果匹配完的返回结果不为空直接返回
                return result;
            } else if (force) { // 如果匹配完的返回结果为空，且force=true强制执行，则返回空结果
                logger.warn("The route result is empty and force execute. consumer: " + NetUtils.getLocalHost() + ", service: " + url.getServiceKey() + ", router: " + url.getParameterAndDecoded(RULE_KEY));
                return result;
            }
        } catch (Throwable t) {
            logger.error("Failed to execute condition router rule: " + getUrl() + ", invokers: " + invokers + ", cause: " + t.getMessage(), t);
        }
        return invokers; // 默认返回原Invoker集合
    }

    @Override
    public boolean isRuntime() {
        // We always return true for previously defined Router, that is, old Router doesn't support cache anymore.
//        return true;
        return this.url.getParameter(RUNTIME_KEY, false);
    }

    @Override
    public URL getUrl() {
        return url;
    }

    boolean matchWhen(URL url, Invocation invocation) {
        return CollectionUtils.isEmptyMap(whenCondition) || matchCondition(whenCondition, url, null, invocation);
    }

    private boolean matchThen(URL url, URL param) {
        return CollectionUtils.isNotEmptyMap(thenCondition) && matchCondition(thenCondition, url, param, null);
    }

    private boolean matchCondition(Map<String, MatchPair> condition, URL url, URL param, Invocation invocation) {
        Map<String, String> sample = url.toMap();
        boolean result = false;
        for (Map.Entry<String, MatchPair> matchPair : condition.entrySet()) {
            String key = matchPair.getKey();
            String sampleValue;
            //get real invoked method name from invocation
            if (invocation != null && (METHOD_KEY.equals(key) || METHODS_KEY.equals(key))) {
                sampleValue = invocation.getMethodName();
            } else if (ADDRESS_KEY.equals(key)) {
                sampleValue = url.getAddress();
            } else if (HOST_KEY.equals(key)) {
                sampleValue = url.getHost();
            } else {
                sampleValue = sample.get(key);
                if (sampleValue == null) {
                    sampleValue = sample.get(DEFAULT_KEY_PREFIX + key);
                }
            }
            if (sampleValue != null) {
                if (!matchPair.getValue().isMatch(sampleValue, param)) {
                    return false;
                } else {
                    result = true;
                }
            } else {
                //not pass the condition
                if (!matchPair.getValue().matches.isEmpty()) {
                    return false;
                } else {
                    result = true;
                }
            }
        }
        return result;
    }

    protected static final class MatchPair {
        final Set<String> matches = new HashSet<String>();
        final Set<String> mismatches = new HashSet<String>();

        private boolean isMatch(String value, URL param) {
            if (!matches.isEmpty() && mismatches.isEmpty()) {
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }
                return false;
            }

            if (!mismatches.isEmpty() && matches.isEmpty()) {
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }
                return true;
            }

            if (!matches.isEmpty() && !mismatches.isEmpty()) {
                //when both mismatches and matches contain the same value, then using mismatches first
                for (String mismatch : mismatches) {
                    if (UrlUtils.isMatchGlobPattern(mismatch, value, param)) {
                        return false;
                    }
                }
                for (String match : matches) {
                    if (UrlUtils.isMatchGlobPattern(match, value, param)) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }
    }
}
