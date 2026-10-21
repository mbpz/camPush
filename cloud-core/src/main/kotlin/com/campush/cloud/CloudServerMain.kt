package com.campush.cloud

/** 本地启动 cloud-core：默认 127.0.0.1:18090，配置可用 CAMPUSH_* 环境变量覆盖。 */
fun main() {
    val config = CloudConfig.fromEnv()
    val server = CloudServer(config)
    val port = server.start(18090)
    println("CamPush CloudServer listening on http://127.0.0.1:$port")
    println("  config page: http://127.0.0.1:$port/config")
    println("  whip backend: ${config.whipHost}:${config.whipApiPort}")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}
