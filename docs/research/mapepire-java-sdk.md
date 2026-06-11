<!-- Research notes generated during initial development (June 2026). Facts were verified against the cited sources at that time; re-verify versions before relying on them. -->

# mapepire-java SDK — Research Report

## 1. Maven Coordinates & Latest Version

**`io.github.mapepire-ibmi:mapepire-sdk`** — latest (and only) release: **`0.1.2`**

- Verified via `https://repo1.maven.org/maven2/io/github/mapepire-ibmi/mapepire-sdk/maven-metadata.xml`: `<latest>0.1.2</latest>`, `<release>0.1.2</release>`, lastUpdated 2025-04-16. (Note: the `search.maven.org` solrsearch index returns 0 hits for "mapepire" — the artifact exists but isn't indexed there; the repo1 metadata and the README's Maven Central badge both confirm 0.1.2.)
- Repo `pom.xml` on `main` also declares version `0.1.2`.

```xml
<dependency>
    <groupId>io.github.mapepire-ibmi</groupId>
    <artifactId>mapepire-sdk</artifactId>
    <version>0.1.2</version>
</dependency>
```

Transitive deps: `org.java-websocket:Java-WebSocket:1.5.7`, `com.fasterxml.jackson.core:jackson-databind:2.17.2`.

## 2. Minimum Java Version

**Java 8** (README: "Java 8 or later"; pom: `maven.compiler.source/target = 1.8`).

## 3. Packages & Core Classes (verified against `main` source)

NOTE: the Java **package** uses an underscore: `io.github.mapepire_ibmi` (groupId uses a hyphen).

| Class | Package |
|---|---|
| `SqlJob`, `Query`, `Pool`, `Tls` | `io.github.mapepire_ibmi` |
| `DaemonServer`, `QueryOptions`, `QueryResult<T>`, `QueryMetadata`, `ColumnMetadata`, `JDBCOptions`, `PoolOptions`, `PoolAddOptions`, `ConnectionResult`, `JobStatus`, `QueryState` | `io.github.mapepire_ibmi.types` |

Key signatures:

```java
// DaemonServer — port defaults to 8076; rejectUnauthorized defaults to TRUE
DaemonServer(String host, int port, String user, String password)
DaemonServer(String host, int port, String user, String password, boolean rejectUnauthorized)
DaemonServer(String host, int port, String user, String password, boolean rejectUnauthorized, String ca)

// SqlJob
SqlJob(); SqlJob(JDBCOptions options)
CompletableFuture<ConnectionResult> connect(DaemonServer db2Server)
Query query(String sql)
Query query(String sql, QueryOptions opts)
<T> CompletableFuture<QueryResult<T>> execute(String sql)             // one-shot, auto-closes
<T> CompletableFuture<QueryResult<T>> execute(String sql, QueryOptions opts)
Query clCommand(String cmd)
void close()                                                          // synchronous

// Query
<T> CompletableFuture<QueryResult<T>> execute()        // default fetches 100 rows
<T> CompletableFuture<QueryResult<T>> execute(int rowsToFetch)
<T> CompletableFuture<QueryResult<T>> fetchMore()      // reuses last rowsToFetch
<T> CompletableFuture<QueryResult<T>> fetchMore(int rowsToFetch)
CompletableFuture<String> close()
QueryState getState()

// QueryOptions
QueryOptions()
QueryOptions(boolean isTerseResults, boolean isClCommand, List<Object> parameters)
void setParameters(List<Object> parameters)
```

## Complete Example (connect, SELECT, metadata, rows, pagination, close)

```java
import java.util.List;
import java.util.Map;

import io.github.mapepire_ibmi.Query;
import io.github.mapepire_ibmi.SqlJob;
import io.github.mapepire_ibmi.types.ColumnMetadata;
import io.github.mapepire_ibmi.types.DaemonServer;
import io.github.mapepire_ibmi.types.QueryResult;

public final class App {
    public static void main(String[] args) throws Exception {
        // rejectUnauthorized=false => accept self-signed certs (Java SDK's
        // equivalent of Node's ignoreUnauthorized=true). Default port: 8076.
        DaemonServer creds = new DaemonServer("MYIBMI", 8076, "USER", "PASSWORD", false);

        SqlJob job = new SqlJob();
        job.connect(creds).get();   // CompletableFuture<ConnectionResult>

        Query query = job.query("SELECT * FROM SAMPLE.DEPARTMENT");
        QueryResult<Object> result = query.execute(50).get();  // fetch first 50 rows

        // Column metadata
        for (ColumnMetadata col : result.getMetadata().getColumns()) {
            System.out.println(col.getName() + " (" + col.getType()
                    + ", precision=" + col.getPrecision() + ", scale=" + col.getScale() + ")");
        }

        // Rows: each row deserializes as a Map<String,Object> keyed by column name
        for (Object row : result.getData()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) row;
            System.out.println(r.get("DEPTNO") + " | " + r.get("DEPTNAME"));
        }

        // Pagination: keep fetching until is_done
        while (!result.getIsDone()) {
            result = query.<Object>fetchMore(50).get();
            for (Object row : result.getData()) {
                System.out.println(row);
            }
        }

        query.close().get();  // CompletableFuture<String>
        job.close();          // synchronous void
    }
}
```

## 4. Bind Parameters — YES

`QueryOptions` carries a `List<Object> parameters`; `Query.execute()` sends them as `"parameters"` in the websocket request (statement runs as `prepare_sql_execute` when prepared).

```java
import java.util.Arrays;
import io.github.mapepire_ibmi.types.QueryOptions;

QueryOptions opts = new QueryOptions(false, false, Arrays.asList("A00"));
//                              isTerseResults, isClCommand, parameters
Query q = job.query("SELECT * FROM SAMPLE.DEPARTMENT WHERE ADMRDEPT = ?", opts);
QueryResult<Object> res = q.execute().get();
q.close().get();
```

One-shot equivalent: `job.execute("SELECT ... WHERE c = ?", opts).get()` (auto-closes the query).

## 5. Pool (async job pool)

```java
import io.github.mapepire_ibmi.Pool;
import io.github.mapepire_ibmi.types.PoolOptions;

PoolOptions poolOpts = new PoolOptions(creds, /* maxSize */ 5, /* startingSize */ 3);
// or PoolOptions(creds, jdbcOptions, maxSize, startingSize)
Pool pool = new Pool(poolOpts);
pool.init().get();                                   // CompletableFuture<Void>

QueryResult<Object> r = pool.<Object>execute("VALUES (JOB_NAME)").get(); // round-robin job
Query q = pool.query("SELECT * FROM SAMPLE.DEPARTMENT");                 // pool.query(sql[, opts])

SqlJob job1 = pool.getJob();                         // freest ready job (sync)
SqlJob job2 = pool.waitForJob().get();               // wait for a ready job
SqlJob job3 = pool.popJob().get();                   // take a job OUT of the pool
pool.addJob().get();                                 // grow pool (also addJob(PoolAddOptions))
pool.end();                                          // close all jobs
```

## 6. TLS / Self-Signed Certs

- Connection is **always TLS**: `wss://host:port/db/` with HTTP Basic auth header; **default port 8076**; 5000 ms handshake timeout.
- The Java SDK flag is **`rejectUnauthorized`** (default **`true`**), not `ignoreUnauthorized` (that's the Node.js SDK name). Set it **`false`** to accept self-signed certs — `checkServerTrusted` then skips all validation.
- Cleaner alternative: pin the server cert via `Tls.getCertificate(DaemonServer)` which returns `CompletableFuture<String>` (PEM), then pass it as the `ca` arg: `new DaemonServer(host, 8076, user, pass, true, caPem)`. A custom CA is tried first, falling back to the JDK trust store.
- **Gotchas:** `SqlJob.getChannel()` (and `Tls.getCertificate`) call `SSLContext.setDefault(...)`, mutating the JVM-wide default SSL context — can affect other TLS connections in the same process. `Tls.getCertificate` installs a trust-everything context to grab the cert. Also confirm the mapepire-server daemon is listening on 8076 on the IBM i.

## 7. QueryResult JSON Shape

`QueryResult<T> extends ServerResponse`. Serialized JSON (from README, matches field mapping):

```json
{
  "id": "query3",
  "success": true,
  "error": null,
  "sql_rc": 0,
  "sql_state": null,
  "execution_time": 174,
  "metadata": {
    "column_count": 5,
    "columns": [
      { "display_size": 3, "label": "DEPTNO", "name": "DEPTNO",
        "type": "CHAR", "precision": 3, "scale": 0 }
    ],
    "job": "058971/QUSER/QZDASOINIT",
    "parameters": null
  },
  "is_done": false,
  "has_results": true,
  "update_count": -1,
  "data": [
    { "DEPTNO": "A00", "DEPTNAME": "SPIFFY COMPUTER SERVICE DIV.",
      "MGRNO": "000010", "ADMRDEPT": "A00", "LOCATION": null }
  ],
  "parameter_count": 0,
  "output_parms": null
}
```

- **`data`**: list of maps (column name → value); Jackson deserializes each row into `LinkedHashMap<String,Object>` (the `<T>` generic is effectively erased — raw `QueryResult.class` is used internally).
- **`is_done`** (`getIsDone()`): `false` while more rows remain → loop `fetchMore()`. **`has_results`** (`getHasResults()`): `true` for result-set-producing statements.
- **`update_count`** (`getUpdateCount()`): `-1` for SELECTs; row count for INSERT/UPDATE/DELETE (where `has_results` is `false`).
- `ColumnMetadata` full fields: `display_size`, `label`, `name`, `type`, `precision`, `scale`, plus `autoIncrement`, `nullable` (int), `readOnly`, `writeable`, `table`.
- Errors: failed queries reject the future with `java.sql.SQLException` (message = `error, sql_state, sql_rc`); also `ClientException` for misuse (re-executing a run query, `rowsToFetch <= 0`, fetchMore before execute/after done).

Sources verified: `https://repo1.maven.org/maven2/io/github/mapepire-ibmi/mapepire-sdk/maven-metadata.xml`, repo `README.md`, `pom.xml`, and `src/main/java/io/github/mapepire_ibmi/{SqlJob,Query,Pool,Tls}.java` + `types/{DaemonServer,QueryOptions,QueryResult,QueryMetadata,ColumnMetadata,PoolOptions,PoolAddOptions}.java` (branch `main`, fetched 2026-06-10).