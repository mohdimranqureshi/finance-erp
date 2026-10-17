@echo off
setlocal
if "%~1"=="" (
  echo Usage: scripts\restore-test-db.bat backups\finance_erp-YYYYMMDD-HHMMSS.dump
  echo This restores to a NEW database named finance_erp_restore_test. It does not overwrite finance_erp.
  exit /b 2
)
if not exist "%~1" (
  echo Backup file not found: %~1
  exit /b 2
)
if "%POSTGRES_PASSWORD%"=="" set "POSTGRES_PASSWORD=local_dev_only_change_me"
echo This creates/replaces only finance_erp_restore_test. Ensure no important data is in that test database.
set /p CONFIRM=Type RESTORE to continue: 
if /I not "%CONFIRM%"=="RESTORE" exit /b 1
docker exec -e PGPASSWORD=%POSTGRES_PASSWORD% finance-erp-postgres dropdb -U finance_erp --if-exists finance_erp_restore_test
docker exec -e PGPASSWORD=%POSTGRES_PASSWORD% finance-erp-postgres createdb -U finance_erp finance_erp_restore_test
if errorlevel 1 exit /b 1
type "%~1" | docker exec -i -e PGPASSWORD=%POSTGRES_PASSWORD% finance-erp-postgres pg_restore -U finance_erp -d finance_erp_restore_test --no-owner --no-privileges
if errorlevel 1 (
  echo Restore test failed. The original finance_erp database was not targeted.
  exit /b 1
)
echo Restore test completed. Inspect finance_erp_restore_test before declaring backups verified.
endlocal
