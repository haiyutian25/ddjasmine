$errs = $null
$tokens = $null
[System.Management.Automation.Language.Parser]::ParseFile('d:\architecture-templates-multimodule\customizer.ps1', [ref]$tokens, [ref]$errs) | Out-Null
if ($errs -and $errs.Count) { $errs | ForEach-Object { $_.Message } } else { 'SYNTAX OK' }
