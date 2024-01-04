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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * This class select one provider from multiple providers randomly.
 * You can define weights for each provider:
 * If the weights are all the same then it will use random.nextInt(number of invokers).
 * If the weights are different then it will use random.nextInt(w1 + w2 + ... + wn)
 * Note that if the performance of the machine is better than others, you can set a larger weight.
 * If the performance is not so good, you can set a smaller weight.
 */
public class RandomLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "random";

    /**
     * Select one invoker between a list using a random criteria
     * @param invokers List of possible invokers
     * @param url URL
     * @param invocation Invocation
     * @param <T>
     * @return The selected invoker
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers
        int length = invokers.size();  // invoker的总数
        // Every invoker has the same weight?
        boolean sameWeight = true; // 每个invoker的权重是否一样
        // the weight of every invokers
        int[] weights = new int[length]; // 每个invoker的权重值
        // the first invoker's weight
        int firstWeight = getWeight(invokers.get(0), invocation); // 第一个invoker的权重值
        weights[0] = firstWeight;
        // The sum of weights
        int totalWeight = firstWeight; // 所有invoker的权重值之和
        for (int i = 1; i < length; i++) {
            int weight = getWeight(invokers.get(i), invocation);
            // save for later use
            weights[i] = weight; // 将权重值保存到weights数组中
            // Sum
            totalWeight += weight;
            if (sameWeight && weight != firstWeight) { // 校验得出，权重是否一样，如果有一个不一样，则将sameWeight置为false
                sameWeight = false;
            }
        }
        if (totalWeight > 0 && !sameWeight) { // invoker权重值不同的计算方法
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            int offset = ThreadLocalRandom.current().nextInt(totalWeight); // 产出一个[0~totalWeight]随机数(不包含totalWeight).
            // Return a invoker based on the random value.
            // 根据产出的随机数，获取invoker
            // 以下逻辑可以这么理解
            // 举例，有A、B两个服务，权重分别是2、3
            // 权重值之和为5，随机数区间为[0,1,2,3,4]，A服务被选中的概率为2/5，取值范围[0,1]，B服务被选中的概率为3/5，取值范围[2,3,4]
            // 如果随机数为4，则命中B服务；如果随机数为1，则命中A服务
            for (int i = 0; i < length; i++) {
                offset -= weights[i];
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(ThreadLocalRandom.current().nextInt(length));
    }

}
