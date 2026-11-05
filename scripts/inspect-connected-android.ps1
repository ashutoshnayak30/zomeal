param([Parameter(Mandatory=$true)][string]$Command)
$client = [Net.Sockets.TcpClient]::new('127.0.0.1',5037)
$stream = $client.GetStream()
$stream.ReadTimeout = 15000
function Read-Exact([int]$Length) {
    $data = New-Object byte[] $Length
    $offset = 0
    while ($offset -lt $Length) {
        $count = $stream.Read($data,$offset,$Length-$offset)
        if ($count -eq 0) { throw 'ADB closed connection' }
        $offset += $count
    }
    return [Text.Encoding]::UTF8.GetString($data)
}
function Send-Adb([string]$Request) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Request)
    $header = [Text.Encoding]::ASCII.GetBytes(('{0:x4}' -f $bytes.Length))
    $stream.Write($header,0,4)
    $stream.Write($bytes,0,$bytes.Length)
    $status = Read-Exact 4
    if ($status -ne 'OKAY') { throw "ADB request failed: $status" }
}
try {
    Send-Adb 'host:transport-usb'
    Send-Adb ('shell:' + $Command)
    $buffer = New-Object byte[] 65536
    $result = [IO.MemoryStream]::new()
    while (($count = $stream.Read($buffer,0,$buffer.Length)) -gt 0) { $result.Write($buffer,0,$count) }
    [Text.Encoding]::UTF8.GetString($result.ToArray())
} finally { $client.Close() }
