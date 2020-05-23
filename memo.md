# 現状

* 循環参照が発生したときに通知するシステムが必要

# ログ

## 20200524_143122

save_package_deps_start: 2152
dep_calc_start: 1266
dep_calc_failed: 148
dep_calc_complete: 1020
dep_calc_complete(empty): 278

dep_calc_get_latests_version: 962
dep_calc_get_deps_first: 960
dep_calc_get_deps_second: 742 // => ここまできてれば全部OK

## 20200524_145040

- save_package_deps_start: 2147
- dep_calc_start: 1268

- dep_calc_complete: 1021
- dep_calc_complete(empty): 279

- dep_calc_start(nonempty): 989
- dep_calc_get_latests_version: 962
  - dep_calc_failed_on_get_latest_version: 26
   - 1個終わってないやつがいる
- dep_calc_get_deps_first: 960
  - dep_calc_failed_on_get_deps_first: 0
    - 2個終わってないやつがいる
- dep_calc_get_deps_second: 742
  - dep_calc_failed_on_get_deps_second: 120
  - 98個終わってないやつがいる
- dep_calc_complete(nonempty): 1021-279=742

## 20200523_154417


- dep_calc_start: 1267
- dep_calc_complete: 1021
  - dep_calc_complete(empty): 279
- dep_calc_start(nonempty): 988
- dep_calc_get_latests_version: 962
  - dep_calc_failed_on_get_latest_version: 62
- dep_calc_get_deps_first: 960
  - dep_calc_failed_on_get_deps_first: 0
  - 2個終わってない
- dep_calc_get_deps_second: 742
  - dep_calc_failed_on_get_deps_second: 120
  - 98個終わってないやつがいる
- dep_calc_complete(nonempty): 1021-279=742