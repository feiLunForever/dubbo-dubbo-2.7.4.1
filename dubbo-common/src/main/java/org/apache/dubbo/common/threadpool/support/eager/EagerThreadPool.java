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

package org.apache.dubbo.common.threadpool.support.eager;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.threadlocal.NamedInternalThreadFactory;
import org.apache.dubbo.common.threadpool.ThreadPool;
import org.apache.dubbo.common.threadpool.support.AbortPolicyWithReport;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.ALIVE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.CORE_THREADS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_ALIVE;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_CORE_THREADS;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_QUEUES;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_THREAD_NAME;
import static org.apache.dubbo.common.constants.CommonConstants.QUEUES_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREADS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.THREAD_NAME_KEY;

/**
 * EagerThreadPool
 * When the core threads are all in busy,
 * create new thread instead of putting task into blocking queue.
 * 优先创建工作线程的线程池
 *     当前线程数小于corePoolSize时，创建工作线程；
 *     当前线程数大于corePoolSize，小于maximumPoolSize时，继续创建工作线程；
 *     只有当前线程数大于maximumPoolSize时，才将任务添加至队列；
 *     队列满时，再有新的线程进来，则抛出RejectedExecutionException。
 *         它的这种策略是为了尽可能的降低远程调用的响应时间来设计的，
 *         如果按照默认的线程处理策略，当前线程数大于corePoolSize，
 *         小于maximumPoolSize时，是先放队列，而不是创建工作线程，等队列满了才创建线程，
 *         显而易见，当并发量大时，由于没有立刻创建线程处理，而是先放到队列，肯定会影响远程调用的性能。
 */
public class EagerThreadPool implements ThreadPool {

    @Override
    public Executor getExecutor(URL url) {
        // 线程池名前缀，默认为DubboXXX
        // 服务提供者的线程池名，默认为DubboServerHandler-IP:PORT
        // 服务客户端的线程池名，默认为DubboClientHandler-IP:PORT
        String name = url.getParameter(THREAD_NAME_KEY, DEFAULT_THREAD_NAME);
        // 核心线程数，默认为0
        int cores = url.getParameter(CORE_THREADS_KEY, DEFAULT_CORE_THREADS);
        // 最大线程数，默认为Integer的最大值
        int threads = url.getParameter(THREADS_KEY, Integer.MAX_VALUE);
        // 队列大小，默认为0
        int queues = url.getParameter(QUEUES_KEY, DEFAULT_QUEUES);
        // 线程存活时间，默认为1分钟
        int alive = url.getParameter(ALIVE_KEY, DEFAULT_ALIVE);

        // init queue and executor
        // 初始化线程池和队列
        // TaskQueue是Dubbo自实现的队列
        TaskQueue<Runnable> taskQueue = new TaskQueue<Runnable>(queues <= 0 ? 1 : queues);
        EagerThreadPoolExecutor executor = new EagerThreadPoolExecutor(cores,
                threads,
                alive,
                TimeUnit.MILLISECONDS,
                taskQueue,
                new NamedInternalThreadFactory(name, true),
                new AbortPolicyWithReport(name, url));
        taskQueue.setExecutor(executor);
        return executor;
    }
}
