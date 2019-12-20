package package_manager_server

import com.gilt.gfc.semver.SemVer

implicit class VersionCondition(val src:String){
  // TODO: write validaiton code
  def valid(ver: SemVer): Boolean = ???
}
