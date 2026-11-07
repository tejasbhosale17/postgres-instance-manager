# Local end-to-end demo

This demo creates a disposable managed PostgreSQL instance, writes a value,
backs it up, changes the value, restores the backup, and reads the original
value again. Run it only against a local test/development installation with
Docker available. It uses a temporary database password entered interactively
and leaves the instance running unless you perform the optional confirmed
deletion at the end.

## Start the application

Follow the [local development setup](../README.md#local-development), including
creating `.env` with fresh local keys and bootstrap administrator credentials.
In one PowerShell window:

```powershell
docker compose up -d metadata-db
.\mvnw.cmd spring-boot:run
```

In a second PowerShell window, set the bootstrap username from `.env` and run
the following. When prompted, enter the bootstrap administrator password and
then a disposable database password of at least 12 characters.

## Authenticate and create an instance

```powershell
$baseUri = 'http://127.0.0.1:8080'
$adminUsername = Read-Host 'Bootstrap administrator username'
$adminPassword = Read-Host 'Bootstrap administrator password' -AsSecureString
$adminPlaintext = [System.Net.NetworkCredential]::new('', $adminPassword).Password
$loginBody = @{ username = $adminUsername; password = $adminPlaintext } | ConvertTo-Json
$session = Invoke-RestMethod -Method Post `
  -Uri "$baseUri/api/auth/login" `
  -ContentType 'application/json' `
  -Body $loginBody
$headers = @{ Authorization = "Bearer $($session.accessToken)" }
$adminPlaintext = $null
$loginBody = $null

$databasePassword = Read-Host 'Disposable PostgreSQL password (12-128 characters)' -AsSecureString
$databasePlaintext = [System.Net.NetworkCredential]::new('', $databasePassword).Password
$createBody = @{
  name = 'demo-postgres'
  databasePassword = $databasePlaintext
} | ConvertTo-Json
$accepted = Invoke-RestMethod -Method Post `
  -Uri "$baseUri/api/instances" `
  -Headers $headers `
  -ContentType 'application/json' `
  -Body $createBody
$instanceOperation = $accepted.operationId
$createBody = $null
```

Define a polling helper and wait for provisioning:

```powershell
function Wait-Operation([string]$OperationId) {
  $deadline = [DateTimeOffset]::UtcNow.AddMinutes(5)
  do {
    $operation = Invoke-RestMethod -Method Get `
      -Uri "$baseUri/api/operations/$OperationId" -Headers $headers
    if ($operation.state -eq 'SUCCEEDED') { return $operation }
    if ($operation.state -eq 'FAILED') {
      throw "Operation $OperationId failed: $($operation.errorCode)"
    }
    Start-Sleep -Seconds 2
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "Operation $OperationId did not finish within five minutes."
}

Wait-Operation $instanceOperation
```

The accepted response identifies the operation. Retrieve the instance ID from
the operation record if it is not included in the accepted response:

```powershell
$completedCreate = Invoke-RestMethod -Method Get `
  -Uri "$baseUri/api/operations/$instanceOperation" -Headers $headers
$instanceId = $completedCreate.instanceId
$instance = Invoke-RestMethod -Method Get `
  -Uri "$baseUri/api/instances/$instanceId" -Headers $headers
$instance | Format-List id, displayName, state, hostPort
```

## Verify backup and restore

This uses `psql` on the host. Keep the password in the process environment only
for the commands that need it; do not save it in a script or shell profile.

```powershell
$databasePlaintext = [System.Net.NetworkCredential]::new('', $databasePassword).Password
$env:PGPASSWORD = $databasePlaintext
psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p $instance.hostPort `
  -U postgres -d postgres `
  -c "CREATE TABLE demo_probe (id integer PRIMARY KEY, value text NOT NULL); INSERT INTO demo_probe VALUES (1, 'before-backup');"

$backupAccepted = Invoke-RestMethod -Method Post `
  -Uri "$baseUri/api/instances/$instanceId/backups" -Headers $headers
Wait-Operation $backupAccepted.operationId
$backups = Invoke-RestMethod -Method Get -Uri "$baseUri/api/backups" -Headers $headers
$backup = $backups | Where-Object { $_.sourceInstanceId -eq $instanceId } |
  Sort-Object createdAt -Descending | Select-Object -First 1
if (-not $backup) { throw 'No backup record was returned for the demo instance.' }

$archivePath = Join-Path $PWD "postgres-backup-$($backup.id).dump"
Invoke-WebRequest -Method Get `
  -Uri "$baseUri/api/backups/$($backup.id)/content" `
  -Headers $headers -OutFile $archivePath

psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p $instance.hostPort `
  -U postgres -d postgres `
  -c "UPDATE demo_probe SET value = 'after-backup' WHERE id = 1;"
psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p $instance.hostPort `
  -U postgres -d postgres -c 'SELECT id, value FROM demo_probe;'

$restoreBody = @{
  backupId = $backup.id
  confirmDataReplacement = $true
} | ConvertTo-Json
$restoreAccepted = Invoke-RestMethod -Method Post `
  -Uri "$baseUri/api/instances/$instanceId/restore" `
  -Headers $headers -ContentType 'application/json' -Body $restoreBody
Wait-Operation $restoreAccepted.operationId
psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p $instance.hostPort `
  -U postgres -d postgres -c 'SELECT id, value FROM demo_probe;'

Remove-Item Env:PGPASSWORD
$databasePlaintext = $null
```

The last query should show `before-backup`, proving the backup restored the
previous database contents. The downloaded archive is a local plaintext copy;
remove it when no longer needed, using host-appropriate secure disposal if
required, and keep the application-managed archive according to the host's
retention policy.

## Optional cleanup

Deleting the instance permanently removes its managed data volume. Only do
this for a disposable demo instance, and confirm the exact ID before sending:

```powershell
$confirmId = Read-Host "Type the exact instance ID to permanently delete: $instanceId"
if ($confirmId -ne $instanceId) { throw 'Instance ID did not match; deletion cancelled.' }
$deleteAccepted = Invoke-RestMethod -Method Delete `
  -Uri "$baseUri/api/instances/$instanceId" -Headers $headers `
  -ContentType 'application/json' -Body '{"confirmDataDeletion":true}'
Wait-Operation $deleteAccepted.operationId
```
