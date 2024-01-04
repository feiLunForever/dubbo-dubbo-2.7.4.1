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
import org.apache.dubbo.rpc.support.RpcUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;

/**
 * ConsistentHashLoadBalance
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "consistenthash";

    /**
     * Hash nodes name
     */
    public static final String HASH_NODES = "hash.nodes";

    /**
     * Hash arguments name
     */
    public static final String HASH_ARGUMENTS = "hash.arguments";

    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    @SuppressWarnings("unchecked")
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 获取方法名
        // 数据示例：sayHello
        String methodName = RpcUtils.getMethodName(invocation);
        // 获取一致性选择器的key
        // 数据示例：com.yuqiao.deeplearningdubbo.analysis.base.DemoService.sayHello
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;
        // 获取invokers的hashcode
        int identityHashCode = System.identityHashCode(invokers);
        // 获取一致性哈希选择器对象
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);
        // 如果没有，则初始化一致性哈希选择器对象ConsistentHashSelector
        if (selector == null || selector.identityHashCode != identityHashCode) {
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, identityHashCode));
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }
        // 通过一致性哈希选择器对象，选择服务提供者Invoker
        return selector.select(invocation);
    }

    private static final class ConsistentHashSelector<T> {
        // 虚拟Invoker集合
        // 用于设置在Hash轮上的虚拟节点
        private final TreeMap<Long, Invoker<T>> virtualInvokers;

        private final int replicaNumber; // 一致性哈希虚拟节点总数

        private final int identityHashCode; // 原Invoker集合的hashcode

        private final int[] argumentIndex; // 选择Invoker时，用于计算hashcode的参数下标

        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>(); // 初始化虚拟节点
            this.identityHashCode = identityHashCode;
            URL url = invokers.get(0).getUrl();
            // 获取虚拟节点数参数，默认160个虚拟节点
            this.replicaNumber = url.getMethodParameter(methodName, HASH_NODES, 160);
            // 获取选择Invoker时，用于计算hashcode的参数下标
            // 比如是0，则在调用时，获取第一个参数的hashcode，跟虚拟节点比对，看落在那个节点上
            String[] index = COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, HASH_ARGUMENTS, "0"));
            argumentIndex = new int[index.length];
            for (int i = 0; i < index.length; i++) {
                argumentIndex[i] = Integer.parseInt(index[i]);
            }
            // 设置虚拟节点
            for (Invoker<T> invoker : invokers) {
                String address = invoker.getUrl().getAddress();
                // 为每个invoker设置replicaNumber个虚拟节点
                // 所有虚拟节点都存储到TreeMap中
                for (int i = 0; i < replicaNumber / 4; i++) {
                    byte[] digest = md5(address + i); // 计算【address + i】的md5值
                    for (int h = 0; h < 4; h++) { // 每个digest计算4次，设置4个虚拟节点
                        long m = hash(digest, h);
                        virtualInvokers.put(m, invoker);
                    }
                }
            }
        }

        public Invoker<T> select(Invocation invocation) {
            String key = toKey(invocation.getArguments()); // 获取参数key
            byte[] digest = md5(key); // 获取参数key的md5值
            return selectForKey(hash(digest, 0)); // 根据参数的md5值，选择Invoker
        }

        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            for (int i : argumentIndex) { // 根据初始化时，确定的参数下标，拼接参数key
                if (i >= 0 && i < args.length) {
                    buf.append(args[i]);
                }
            }
            return buf.toString();
        }

        private Invoker<T> selectForKey(long hash) {
            // 根据TreeMap的特性，选择大于hash的最小的Entry
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.ceilingEntry(hash);
            if (entry == null) { // 容错逻辑，当不存在时，默认选择第一个
                entry = virtualInvokers.firstEntry();
            }
            return entry.getValue(); // 返回Invoker
        }

        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }

        private byte[] md5(String value) {
            MessageDigest md5;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            md5.reset();
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            md5.update(bytes);
            return md5.digest();
        }

    }

}
