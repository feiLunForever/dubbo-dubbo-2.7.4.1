# 环境搭建

在生产环境下，Dubbo 依赖的环境非常少，只依赖一个注册中心和一个管理后台。其中，Dubbo 只强制依赖注册中心，管理后台是一个非强制依赖的管理工具。

接下来，我们就以 Zookeeper 为注册中心，搭建 Dubbo 环境，包括安装注册中心和管理后台。

> **温馨提示**
>
> 本文搭建的是有注册中心的生产环境，不是无注册中心的Demo环境。

## 安装注册中心

1. 从 Zookeeper 官网下载一个**最新最稳定**版本的安装包。

[zookeeper.apache.org/releases.ht…](https://link.juejin.cn/?target=https%3A%2F%2Fzookeeper.apache.org%2Freleases.html "https://zookeeper.apache.org/releases.html")

2. 将下载好的安装包解压至本地，并进入`conf`目录。

```shell
cd /Users/jiangbolun/home/zookeeper-3.4.14/conf/
```

3. 在`conf`目录下，创建`zoo.cfg`文件，并填入以下配置信息。

```properties
tickTime=2000
initLimit=10
syncLimit=5
dataDir=//这里替换自己的目录
clientPort=2181
```

注意，上面的`dataDir`是存储 Zookeeper 数据的目录，要替换成自己的本地目录。

4. 进入bin目录。

```shell
cd ../bin/
```

5. 执行以下命令，启动 Zookeeper 服务。

```shell
./zkServer.sh start
```

执行完此命令后，如果出现以下信息，就表示启动成功。

```shell
ZooKeeper JMX enabled by default
Using config: /Users/jiangbolun/home/zookeeper-3.4.14/bin/../conf/zoo.cfg
Starting zookeeper ... STARTED
```

至此，注册中心就安装完成并启动了。
