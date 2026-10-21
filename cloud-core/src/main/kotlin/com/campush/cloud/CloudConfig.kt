package com.campush.cloud

/**
 * 云端服务对外地址配置。
 *
 * 默认值指向本地 Docker 开发环境（SRS 域名 srs.m0-wan.orb.local，页面 8080 / 信令 1985）。
 * 可在 cloud-core 的 `/config` 配置页（或 `POST /api/v1/config`）修改，运行期生效，无需改代码。
 */
data class CloudConfig(
    val apiHost: String = "http://127.0.0.1:18090",
    val playHost: String = "http://127.0.0.1:18090",
    val whipHost: String = "http://srs.m0-wan.orb.local",
    val whipApiPort: Int = 1985,
    val playbackHost: String = "http://srs.m0-wan.orb.local",
    val playbackPort: Int = 8080,
    val turnUrls: List<String> = listOf("turn:srs.m0-wan.orb.local:3478?transport=udp"),
    val turnSecret: String = "dev-turn-secret",
    val accountToken: String = "acct_dev",
    val maxViewers: Int = 100,
    val maxRooms: Int = 20,
    val maxDurationSec: Int = 43_200,
    val idleRoomTtlMs: Long = 10 * 60 * 1000L,
) {
    companion object {
        fun localDev(): CloudConfig = CloudConfig()

        /** 从环境变量覆盖（部署时无需改配置文件）。 */
        fun fromEnv(env: Map<String, String> = System.getenv()): CloudConfig {
            val base = localDev()
            return base.copy(
                apiHost = env["CAMPUSH_API_HOST"] ?: base.apiHost,
                playHost = env["CAMPUSH_PLAY_HOST"] ?: base.playHost,
                whipHost = env["CAMPUSH_WHIP_HOST"] ?: base.whipHost,
                whipApiPort = env["CAMPUSH_WHIP_API_PORT"]?.toIntOrNull() ?: base.whipApiPort,
                playbackHost = env["CAMPUSH_PLAYBACK_HOST"] ?: base.playbackHost,
                playbackPort = env["CAMPUSH_PLAYBACK_PORT"]?.toIntOrNull() ?: base.playbackPort,
                turnUrls = env["CAMPUSH_TURN_URLS"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: base.turnUrls,
            )
        }
    }
}
