@echo off
setlocal
if "%POSTGRES_PASSWORD%"=="" set "POSTGRES_PASSWORD=local_dev_only_change_me"
if not exist backups mkdir backups
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set STAMP=%%i
echo Creating PostgreSQL custom-format backup: backups\finance_erp-%STAMP%.dump
docker exec -e PGPASSWORD=%POSTGRES_PASSWORD% finance-erp-postgres pg_dump -U finance_erp -d finance_erp -Fc > "backups\finance_erp-%STAMP%.dump"
if errorlevel 1 (
  echo Backup failed. Check Docker and the database password.
  exit /b 1
)
echo Backup created. Verify file size and test restore before relying on it.
endlocal
