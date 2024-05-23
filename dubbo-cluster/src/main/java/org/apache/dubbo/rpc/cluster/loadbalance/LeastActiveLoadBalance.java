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
import org.apache.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 最少活跃调用数算法
 *
 *
 * LeastActiveLoadBalance
 * <p>
 * Filter the number of invokers with the least number of active calls and count the weights and quantities of these invokers.
 * If there is only one invoker, use the invoker directly;
 * if there are multiple invokers and the weights are not the same, then random according to the total weight;
 * if there are multiple invokers and the same weight, then randomly called.
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "leastactive";

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers
        int length = invokers.size(); // Invoker集合大小
        // The least active value of all invokers
        int leastActive = -1; // 所有Invoker中，最小的活跃数
        // The number of invokers having the same least active value (leastActive)
        // 具有相同[最小活跃数]的Invoker数量
        // 比如有3个Invoker，活跃数分别是[3,1,1]，则此时leastCount=2
        int leastCount = 0;
        // The index of invokers having the same least active value (leastActive)
        // 具有相同[最小活跃数]的Invoker的下标
        // 比如有3个Invoker，活跃数分别是[3,1,1]，则此时leastIndexes=[1,2]
        int[] leastIndexes = new int[length];
        // the weight of every invokers
        int[] weights = new int[length]; // 每个Invoker的权重
        // The sum of the warmup weights of all the least active invokers
        int totalWeight = 0; // 具有相同[最小活跃数]的Invoker的权重之和
        // The weight of the first least active invoker
        int firstWeight = 0; // 具有相同[最小活跃数]的Invoker的第一个Invoker的权重
        // Every least active invoker has the same weight value?
        boolean sameWeight = true;  // 具有相同[最小活跃数]的Invoker的权重是否相等


        // Filter out all the least active invokers
        // 过滤出所有具有相同[最小活跃数]的Invoker
        for (int i = 0; i < length; i++) { // 获取所有的invoker并执行计算
            Invoker<T> invoker = invokers.get(i);
            // Get the active number of the invoker
            // // 通过RpcStatus获取当前这个invoker并发数
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive();
            // Get the weight of the invoker's configuration. The default value is 100.
            // 通过预热机制计算权重值
            int afterWarmup = getWeight(invoker, invocation);
            // save for later use
            // 将Invoker的权重值保存至weights数组
            weights[i] = afterWarmup;
            // If it is the first invoker or the active number of the invoker is less than the current least active number
            // 发现最小的活跃数，重新开始计算
            if (leastActive == -1 || active < leastActive) {
                // Reset the active number of the current invoker to the least active number
                // 记录leastActive 为当前的活跃数，并重置最小计数，基于当前最小计数重新计数
                leastActive = active; // 将最小活跃数置为当前Invoker的活跃数
                // Reset the number of least active invokers
                leastCount = 1; // 将最小活跃数的Invoker数量置为1
                // Put the first least active invoker first in leastIndexes
                leastIndexes[0] = i;  // 将最小活跃数的Invoker的下标数组的第一项，保存为当前Invoker的下标
                // Reset totalWeight
                totalWeight = afterWarmup; // 重置[最小活跃数]的Invoker的权重之和
                // Record the weight the first least active invoker
                firstWeight = afterWarmup; // 重置[最小活跃数]的Invoker的第一个Invoker的权重
                // Each invoke has the same weight (only one invoker here)
                sameWeight = true; // 因为是第一个Invoker，或者有了新的更小活跃数，所以此时的sameWeight为true
                // If current invoker's active value equals with leaseActive, then accumulating.
            } else if (active == leastActive) {
                // Record the index of the least active invoker in leastIndexes order
                // 如果当前Invoker的活跃数等于最小活跃数，则进行累加计算
                // 累加具有[最小活跃数]的Invoker数量，并记录其下标
                leastIndexes[leastCount++] = i;
                // Accumulate the total weight of the least active invoker
                totalWeight += afterWarmup; // 累加具有[最小活跃数]的Invoker的权重之和
                // If every invoker has the same weight?
                // 如果当前sameWeight=true，且当前Invoker集合下标>0，且当前Invoker权重不等于第一个Invoker的权重
                //将sameWeight置为false，即具有[最小活跃数]的Invoker权重不相等。
                if (sameWeight && i > 0
                        && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        // Choose an invoker from all the least active invokers
        // 如果只有一个[最小活跃数]的Invoker，则直接返回
        // 如[A,B,C]的活跃数为[2,2,1]，则C的活跃数最少，且只有一个，返回C
        if (leastCount == 1) {
            // If we got exactly one invoker having the least active value, return this invoker directly.
            return invokers.get(leastIndexes[0]);
        }

        // 如果具有[最小活跃数]的Invoker权重不相等，且权重之和大于0，则根据权重随机计算
        if (!sameWeight && totalWeight > 0) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on
            // totalWeight.
            // 参数一个随机权重值.
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            // 根据随机数，返回Invoker.
            // 假定当前有两个最小活跃数的Invoker，A和B，对应的权重是[3,2]
            // 如果产生的offsetWeight=3
            // 那么，3-3=0，不小于0，不满足条件，继续循环
            // 此时，0-2=-2，小于0，满足条件，则返回服务B
            //
            // 如果产生的offsetWeight=2
            // 那么，2-3=-1，小于0，满足条件，则返回服务A
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexes[i]; // 用随机数减去Invoker的权重值
                offsetWeight -= weights[leastIndex];
                if (offsetWeight < 0) { // 如果offsetWeight小于0，则返回当前Invoker
                    return invokers.get(leastIndex);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        // 如果所有具有[最小活跃数]的Invoker权重都相等，则随机返回一个。
        return invokers.get(leastIndexes[ThreadLocalRandom.current().nextInt(leastCount)]);
    }
}
