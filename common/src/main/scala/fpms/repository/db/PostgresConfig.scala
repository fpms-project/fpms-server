package fpms.repository.db

import com.typesafe.config.Config

case class PostgresConfig(host: String, port: Int, database: String, username: String, password: String) {
  def url = s"jdbc:postgresql://$host:$port/$database"
}

object PostgresConfig {
  def apply(config: Config): PostgresConfig =
    PostgresConfig(
      config.getString("host"),
      config.getInt("port"),
      config.getString("database"),
      config.getString("user"),
      config.getString("pass")
    )
}
