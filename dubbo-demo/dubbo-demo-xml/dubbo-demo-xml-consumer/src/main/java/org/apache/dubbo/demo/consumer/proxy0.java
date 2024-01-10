/*
 * Decompiled with CFR.
 *
 * Could not load the following classes:
 *  com.alibaba.dubbo.rpc.service.EchoService
 *  org.apache.dubbo.common.bytecode.ClassGenerator$DC
 *  org.apache.dubbo.demo.DemoService
 */
package org.apache.dubbo.demo.consumer;

import com.alibaba.dubbo.rpc.service.EchoService;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import org.apache.dubbo.common.bytecode.ClassGenerator;
import org.apache.dubbo.demo.DemoService;

public class proxy0
implements ClassGenerator.DC,
EchoService,
DemoService {
    public static Method[] methods;
    private InvocationHandler handler;

    public String sayHello(String string) {
        Object[] objectArray = new Object[]{string};
        Object object = null;
        try {
            object = this.handler.invoke(this, methods[0], objectArray);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        return (String)object;
    }

    public Object $echo(Object object) {
        Object[] objectArray = new Object[]{object};
        Object object2 = null;
        try {
            object2 = this.handler.invoke(this, methods[1], objectArray);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        return object2;
    }

    public proxy0() {
    }

    public proxy0(InvocationHandler invocationHandler) {
        this.handler = invocationHandler;
    }
}