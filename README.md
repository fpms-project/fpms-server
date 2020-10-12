fpmsn
---

**fpmsn** - Fast Package Manager Server for NPM.

# requirements

- redis
- postgres

# preparation

- copy `src/main/resources/app_example.conf` to `src/main/resources/app.conf` and modify it 
- generate json data by [this script](https://github.com/sh4869/get-all-package-info) and put it into the directly 
specified in app.conf (`json.jsondir`)

# How to start the server

## At first time

```bash
sbt run init --prepare
```

## From a second time

```bash
sbt run
```