fpms-server
---

**fpms-server** is a server side program for a package manager which is calculated the set of indirectly-depending packages.

The client of this is [here](https://www.npmjs.com/package/fpms-client).

# How to start the server

## requirements

- redis
- postgres

## preparation

- copy `src/main/resources/app_example.conf` to `src/main/resources/app.conf` and modify it 
- generate json data by [this script](https://github.com/sh4869/get-all-package-info) and put it into the directly 
specified in app.conf (`json.jsondir`)

## At first time

```bash
sbt run init --prepare
```

## From a second time

```bash
sbt run
```

# API

- HOST: `http://fpms-server.cs.ise.shibaura-it.ac.jp`

## `/calculated/$name`

returns the calculated `$name` package.

### optional query

- `range`: version range, see also [semver.npmjs.com](https://semver.npmjs.com/)

### response example

```json
{
  "target": {
    "name": "react",
    "version": "17.0.1",
    "dep": {
      "loose-envify": "^1.1.0",
      "object-assign": "^4.1.1"
    },
    "shasum": "6e0600416bd57574e3f86d92edba3d9008726127",
    "integrity": "sha512-lG9c9UuMHdcAexXtigOZLX8exLWkW0Ku29qPRU8uhF2R9BN96dLCt0psvzPLlHc5OWkgymP3qwTRgbnw5BKx3w=="
  },
  "packages": [
    {
      "name": "object-assign",
      "version": "4.1.1",
      "dep": {},
      "shasum": "2109adc7965887cfc05cbbd442cac8bfbb360863",
      "integrity": ""
    },
    {
      "name": "loose-envify",
      "version": "1.4.0",
      "dep": {
        "js-tokens": "^3.0.0 || ^4.0.0"
      },
      "shasum": "71ee51fa7be4caec1a63839f7e682d8132d30caf",
      "integrity": "sha512-lyuxPGr/Wfhrlem2CL/UcnUc1zcqKAImBDzukY7Y5F/yQiNdko6+fRLevlw1HgMySw7f611UIY408EtxRSoK3Q=="
    },
    {
      "name": "js-tokens",
      "version": "4.0.0",
      "dep": {},
      "shasum": "19203fb59991df98e3a287050d4647cdeaf32499",
      "integrity": "sha512-RdJUflcE3cUzKiMqQgsCu06FPu9UdIJO0beYbPhHN4k6apgJtifcoCtT9bcxOpYBtpD2kCM6Sbzg4CausW/PKQ=="
    }
  ]
}
```

## `POST /add`

added packages to the server.

### body format

```
{
  "id": 0, // always 0
  "name": "package_name", // package name 
  "version": "0.0.0", // semantic version
  "deps": {}, // deps of packages
  "shasum": "" // shasum of package. It can get in npm or yarn api.
}
```