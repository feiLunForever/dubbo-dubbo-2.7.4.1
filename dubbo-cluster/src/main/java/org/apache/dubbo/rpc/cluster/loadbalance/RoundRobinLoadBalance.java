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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance.
 */

/**
 * 1. 给每个服务设置 2 个权重，原始权重(weight)和当前权重(current)，原始权重即给服务设定的权重，当前权重，用于选择目标时的加/减权；
 * 2. 轮询加权，轮询每个服务，服务的当前权重(current)=当前权重(current)+原始权重(weight)；
 * 3. 选出当前权重(current)最大的服务，作为目标服务；
 * 4. 选中后减权，目标服务的当前权重(current)=当前权重(current)-总权重(totalWeight)；
 * 5. 每次调用，重复执行第 2、3、4 步。
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "roundrobin";
    
    private static final int RECYCLE_PERIOD = 60000;
    
    protected static class WeightedRoundRobin {
        private int weight; // 服务原始权重
        private AtomicLong current = new AtomicLong(0); // 服务当前权重
        private long lastUpdate; // 最近更新时间
        public int getWeight() {
            return weight;
        }
        public void setWeight(int weight) {
            // 初始化权重
            // 当前权重=0
            this.weight = weight;
            current.set(0);
        }
        public long increaseCurrent() { // 当前权重 = 当前权重 + 原始权重
            return current.addAndGet(weight);
        }
        public void sel(int total) { // 当前权重 = 当前权重 - 总权重
            current.addAndGet(-1 * total);
        }
        public long getLastUpdate() {
            return lastUpdate;
        }
        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }

    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap = new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();
    private AtomicBoolean updateLock = new AtomicBoolean();
    
    /**
     * get invoker addr list cached for specified invocation
     * <p>
     * <b>for unit test only</b>
     * 
     * @param invokers
     * @param invocation
     * @return
     */
    protected <T> Collection<String> getInvokerAddrList(List<Invoker<T>> invokers, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        Map<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map != null) {
            return map.keySet();
        }
        return null;
    }
    
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 服务key,服务名.方法名
        // 数据示例：com.yuqiao.deeplearningdubbo.analysis.base.DemoService.sayHello
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        // 获取服务KEY对应的轮询权重值map
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map == null) {
            methodWeightMap.putIfAbsent(key, new ConcurrentHashMap<String, WeightedRoundRobin>());
            map = methodWeightMap.get(key);
        }
        int totalWeight = 0; // 所有权重之和
        long maxCurrent = Long.MIN_VALUE; // 用于筛选最大当前权重的中间变量
        long now = System.currentTimeMillis(); // 当前更新时间
        Invoker<T> selectedInvoker = null; // 选中的Invoker
        WeightedRoundRobin selectedWRR = null; // 选中的Invoker对应的WeightedRoundRobin
        for (Invoker<T> invoker : invokers) {
            String identifyString = invoker.getUrl().toIdentityString();
            WeightedRoundRobin weightedRoundRobin = map.get(identifyString);
            int weight = getWeight(invoker, invocation);

            if (weightedRoundRobin == null) { // 初始化服务权重
                weightedRoundRobin = new WeightedRoundRobin();
                weightedRoundRobin.setWeight(weight);
                map.putIfAbsent(identifyString, weightedRoundRobin);
            }
            if (weight != weightedRoundRobin.getWeight()) { // 权重值发生变化
                //weight changed
                weightedRoundRobin.setWeight(weight);
            }
            // 计算当前权重值
            // 轮询加权
            // 当前权重值 = 当前权重值 + 原始权重值
            long cur = weightedRoundRobin.increaseCurrent();
            weightedRoundRobin.setLastUpdate(now);
            if (cur > maxCurrent) { // 选出当前权重值最大的Invoker
                maxCurrent = cur;
                selectedInvoker = invoker;
                selectedWRR = weightedRoundRobin;
            }
            totalWeight += weight; // 计算总权重值
        }
        // 删除长时间未更新的WeightedRoundRobin，默认为60秒
        // 比如某个节点挂了，就更新不到Invoker对应的WeightedRoundRobin了，这时应该移除掉
        if (!updateLock.get() && invokers.size() != map.size()) {
            if (updateLock.compareAndSet(false, true)) {
                try {
                    // copy -> modify -> update reference
                    ConcurrentMap<String, WeightedRoundRobin> newMap = new ConcurrentHashMap<>(map);
                    newMap.entrySet().removeIf(item -> now - item.getValue().getLastUpdate() > RECYCLE_PERIOD);
                    methodWeightMap.put(key, newMap);
                } finally {
                    updateLock.set(false);
                }
            }
        }
        // 选中Invoker之后，将对应的WeightedRoundRobin中的当前权重减去总权重
        // 选中后减权
        // WeightedRoundRobin.current = WeightedRoundRobin.current - totalWeight
        if (selectedInvoker != null) {
            selectedWRR.sel(totalWeight);
            return selectedInvoker;
        }
        // should not happen here
        return invokers.get(0);
    }

}
