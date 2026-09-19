# Run after the backend is started: powershell -ExecutionPolicy Bypass -File .\smoke-test.ps1
$ErrorActionPreference = 'Stop'
$api = 'http://127.0.0.1:8080'
function Assert-Ok([bool]$condition, [string]$message) {
  if (-not $condition) { throw "FAIL: $message" }
  Write-Host "PASS: $message" -ForegroundColor Green
}
function To-Json($value) { return $value | ConvertTo-Json -Depth 5 -Compress }

$status = Invoke-RestMethod "$api/system/status"
Assert-Ok ($status.activeStrategy -in @('AI','RULE')) 'system-status endpoint returns active strategy'
$catalog = @(Invoke-RestMethod "$api/products")
Assert-Ok ($catalog.Count -ge 8) 'catalog endpoint returns seed products'
$apparel = @(Invoke-RestMethod "$api/products?category=APPAREL")
Assert-Ok (@($apparel | Where-Object {$_.category -ne 'APPAREL'}).Count -eq 0) 'category filter works'

$sku = "SMOKE-$([guid]::NewGuid().ToString('N').Substring(0,8))"
$product = Invoke-RestMethod "$api/products" -Method Post -ContentType 'application/json' -Body (To-Json @{sku=$sku;name='API Smoke Test Product';category='HOME';currentPrice=20.00;stockLevel=20;reorderThreshold=10;demandVelocity=1})
Assert-Ok ($product.id) 'create-product endpoint returns an ID'

$changed = Invoke-RestMethod "$api/products/$($product.id)/stock" -Method Patch -ContentType 'application/json' -Body (To-Json @{stockLevel=5})
Assert-Ok ($changed.stockLevel -eq 5) 'stock-update endpoint persists stock'

$price = $null; $reorder = $null
for ($attempt = 0; $attempt -lt 20; $attempt++) {
  Start-Sleep -Seconds 1
  $price = @(Invoke-RestMethod "$api/pricing-suggestions" | Where-Object {$_.product.id -eq $product.id})[0]
  $reorder = @(Invoke-RestMethod "$api/reorder-suggestions" | Where-Object {$_.product.id -eq $product.id})[0]
  if ($price -and $reorder) { break }
}
Assert-Ok ($price -and $reorder) 'low-stock event created price and reorder recommendations'
Assert-Ok ($price.source -and $reorder.source) 'recommendations show AI/rule source'

Invoke-RestMethod "$api/pricing-suggestions/$($price.id)" -Method Patch -ContentType 'application/json' -Body (To-Json @{status='ACCEPTED'}) | Out-Null
Invoke-RestMethod "$api/reorder-suggestions/$($reorder.id)" -Method Patch -ContentType 'application/json' -Body (To-Json @{status='REJECTED'}) | Out-Null
Start-Sleep -Milliseconds 250
$accepted = @(Invoke-RestMethod "$api/pricing-suggestions?status=ACCEPTED" | Where-Object {$_.id -eq $price.id})[0]
$rejected = @(Invoke-RestMethod "$api/reorder-suggestions?status=REJECTED" | Where-Object {$_.id -eq $reorder.id})[0]
$savedProduct = @(Invoke-RestMethod "$api/products" | Where-Object {$_.id -eq $product.id})[0]
Assert-Ok ($accepted.decidedAt -and $rejected.decidedAt) 'decision timestamps are stored'
Assert-Ok ($savedProduct.currentPrice -eq $price.recommendedPrice) 'accepted price changes live product price'
Assert-Ok ($savedProduct.stockLevel -eq 5) 'rejected reorder does not change stock'
Write-Host "`nAPI smoke test completed successfully." -ForegroundColor Cyan
