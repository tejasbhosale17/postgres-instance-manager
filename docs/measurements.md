# Reproducible test-suite measurement

The repository includes `scripts/measure-test-suite.ps1` to measure a clean
Maven test run without claiming a precomputed performance baseline. Run it
from the repository root in PowerShell with Java 21 and a running Docker
Engine:

```powershell
.\scripts\measure-test-suite.ps1
```

The script runs `.\mvnw.cmd -B clean test`, measures wall-clock elapsed time,
and writes the observed result to an ignored JSON file under
`target/measurements/`. Each record includes the commit, Java and Docker
versions, outcome, suite/test counts, and failures, errors, and skipped tests.
The test suite includes Testcontainers-backed PostgreSQL and Docker-managed
PostgreSQL flows, so measurements are only comparable when run with similar
hardware, Docker resources, image cache, and background load.

The JSON output is a measurement of one run, not a performance guarantee.
Repeat the command on an otherwise idle machine and retain each generated file
when comparing changes; do not compare a single run across dissimilar
environments or present elapsed time as application request latency.

## Observed run

On 2026-11-07, the script completed successfully in 92.870 seconds with Java
21.0.10, Maven 3.9.15, and Docker Engine 29.9.2. Surefire reported 6 suites
and 15 tests, with 0 failures, 0 errors, and 0 skipped tests. This is one
local test-suite measurement, not an application performance benchmark.
