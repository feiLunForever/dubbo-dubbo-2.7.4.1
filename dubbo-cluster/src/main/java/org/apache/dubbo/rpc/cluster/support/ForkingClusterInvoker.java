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
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.threadlocal.NamedInternalThreadFactory;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_FORKS;
import static org.apache.dubbo.rpc.cluster.Constants.FORKS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * NOTICE! This implementation does not work well with async call.
 *
 * Invoke a specific number of invokers concurrently, usually used for demanding real-time operations, but need to waste more service resources.
 *
 * <a href="http://en.wikipedia.org/wiki/Fork_(topology)">Fork</a>
 */
public class ForkingClusterInvoker<T> extends AbstractClusterInvoker<T> {

    /**
     * Use {@link NamedInternalThreadFactory} to produce {@link org.apache.dubbo.common.threadlocal.InternalThread}
     * which with the use of {@link org.apache.dubbo.common.threadlocal.InternalThreadLocal} in {@link RpcContext}.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(
            new NamedInternalThreadFactory("forking-cluster-timer", true));

    public ForkingClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            checkInvokers(invokers, invocation); // 检验Invoker集合是否为空
            final List<Invoker<T>> selected;
            // 远程调用的并发执行数，如forks=2，则，同时发起2个远程调用，哪个先成功返回，就取哪个结果
            final int forks = getUrl().getParameter(FORKS_KEY, DEFAULT_FORKS);
            // 执行超时时间
            final int timeout = getUrl().getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);
            if (forks <= 0 || forks >= invokers.size()) {
                selected = invokers;
            } else {
                selected = new ArrayList<>();
                // 循环选择出forks个Invoker，用于并发调用
                for (int i = 0; i < forks; i++) {
                    Invoker<T> invoker = select(loadbalance, invocation, invokers, selected);
                    if (!selected.contains(invoker)) {
                        //Avoid add the same invoker several times.
                        selected.add(invoker);
                    }
                }
            }
            RpcContext.getContext().setInvokers((List) selected);
            final AtomicInteger count = new AtomicInteger();
            // 远程调用结果
            final BlockingQueue<Object> ref = new LinkedBlockingQueue<>();
            // 将并发调用，提交到线程池，执行并发调用
            for (final Invoker<T> invoker : selected) {
                executor.execute(() -> {
                    try {
                        Result result = invoker.invoke(invocation); // 执行远程调用
                        ref.offer(result); // 将远程调用结果，保存至ref
                    } catch (Throwable e) {
                        // 当调用失败时，将异常信息保存至ref
                        int value = count.incrementAndGet();
                        // 这里之所以当调用数大于选中的invoker数，才将异常信息添加至ref
                        // 是为了防止获取到不准确的异常信息
                        // 比如有两个invoker:
                        // 第一个invoker调用时，因为网络等原因故障，返回异常
                        // 第二个invoker调用成功。
                        // 如果没有这个判断，则获取到的结果可能是第一个异常信息，而不是第二个调用成功的结果
                        if (value >= selected.size()) {
                            ref.offer(e);
                        }
                    }
                });
            }
            try {
                // 获取调用结果
                // 并发调用任务中，只要有一个返回了结果，都会立马获取到
                Object ret = ref.poll(timeout, TimeUnit.MILLISECONDS);
                if (ret instanceof Throwable) { // 如果返回结果是异常，则抛出
                    Throwable e = (Throwable) ret;
                    throw new RpcException(e instanceof RpcException ? ((RpcException) e).getCode() : 0, "Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e.getCause() != null ? e.getCause() : e);
                }
                return (Result) ret; // 否则，返回调用结果
            } catch (InterruptedException e) {
                throw new RpcException("Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e);
            }
        } finally {
            // clear attachments which is binding to current thread.
            RpcContext.getContext().clearAttachments();
        }
    }
}
