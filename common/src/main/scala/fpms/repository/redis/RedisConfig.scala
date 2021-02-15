package fpms.repository.redis

import com.typesafe.config.Config

case class RedisConfig(host: String, port: Int)

object RedisConfig {
  def apply(conf: Config): RedisConfig =
    RedisConfig(
      if (conf.hasPath("host")) conf.getString("host") else "localhost",
      if (conf.hasPath("port")) conf.getInt("port") else 6379
    )
}
