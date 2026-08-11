@echo off
setlocal
set "BUNDLE=%~dp0.."
if not exist "%BUNDLE%\runtime-windows\bin\java.exe" (
  powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "$bundle=(Resolve-Path '%BUNDLE%').Path; $runtime=Join-Path $bundle 'runtime-windows'; $tmp=Join-Path $bundle ('.runtime-extract-' + [Guid]::NewGuid().ToString('N')); Expand-Archive -LiteralPath (Join-Path $bundle 'runtime-windows.zip') -DestinationPath $tmp -Force; if (Test-Path $runtime) { Remove-Item -LiteralPath $runtime -Recurse -Force }; Move-Item -LiteralPath (Get-ChildItem -LiteralPath $tmp | Select-Object -First 1).FullName -Destination $runtime; Remove-Item -LiteralPath $tmp -Recurse -Force"
  if errorlevel 1 exit /b %ERRORLEVEL%
)
"%BUNDLE%\runtime-windows\bin\java.exe" -Dloader.main=com.webank.wedatasphere.wdsavs.aiagent.remote.RelayCollaborationCli -cp "%BUNDLE%\app.jar" org.springframework.boot.loader.launch.PropertiesLauncher %*
exit /b %ERRORLEVEL%
