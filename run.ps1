$libs = (Get-ChildItem -Path .\lib -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
java -cp "out;$libs" rulebridge.Main