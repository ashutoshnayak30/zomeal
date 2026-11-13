$ErrorActionPreference = 'Stop'
$keyDirectory = 'D:\Codex\2026-08-09\build\Zomeal-Release-Keys'
if (Test-Path -LiteralPath $keyDirectory) { throw 'Key directory already exists. Reuse existing keys; do not overwrite.' }
New-Item -ItemType Directory -Path $keyDirectory | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $keyDirectory /inheritance:r /grant:r "${identity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect key directory' }
$properties = @()
foreach ($appName in @('customer','provider')) {
    $random = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($random)
    $rng.Dispose()
    $password = [Convert]::ToBase64String($random)
    $env:ZOMEAL_KEY_PASSWORD = $password
    $keyFile = Join-Path $keyDirectory "$appName-upload.p12"
    try {
        & 'C:\Program Files\Java\jdk-17\bin\keytool.exe' -genkeypair -keystore $keyFile -storetype PKCS12 -alias "$appName-upload" -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Zomeal Upload, O=Zomeal, C=IN' -storepass:env ZOMEAL_KEY_PASSWORD -keypass:env ZOMEAL_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Key generation failed' }
        & 'C:\Program Files\Java\jdk-17\bin\keytool.exe' -exportcert -rfc -keystore $keyFile -alias "$appName-upload" -storepass:env ZOMEAL_KEY_PASSWORD -file (Join-Path $keyDirectory "$appName-upload-certificate.pem")
        if ($LASTEXITCODE -ne 0) { throw 'Certificate export failed' }
        $properties += "$appName.storeFile=$($keyFile.Replace('\','/'))", "$appName.keyAlias=$appName-upload", "$appName.storePassword=$password", "$appName.keyPassword=$password"
        [IO.File]::WriteAllLines((Join-Path $keyDirectory 'signing.properties'), $properties)
    } finally { Remove-Item Env:ZOMEAL_KEY_PASSWORD -ErrorAction SilentlyContinue; $password=$null }
}
Write-Output 'Two upload keystores and signing.properties generated. Back up this directory securely. Never commit or send passwords in chat.'
