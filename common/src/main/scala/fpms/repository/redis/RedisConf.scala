package fpms.repository.redis

import com.typesafe.config.Config

case class RedisConf(host: String, port: Int)

object RedisConf {
  def apply(conf: Config): RedisConf =
    RedisConf(
      if (conf.hasPath("host")) conf.getString("host") else "localhost",
      if (conf.hasPath("port")) conf.getInt("port") else 6379
    )
}
