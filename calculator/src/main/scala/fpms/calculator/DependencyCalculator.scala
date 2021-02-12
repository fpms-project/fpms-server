package fpms.calculator

trait DependencyCalculator[F[_]] {

  /**
    * initalize data
    */
  def initialize(): F[Unit]

  def loop(): F[Unit]
}
