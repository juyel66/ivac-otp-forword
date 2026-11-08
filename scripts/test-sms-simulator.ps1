<#
.SYNOPSIS
    IVAC Assistant Mobile — Real-Device & Emulator SMS Test Simulator
.DESCRIPTION
    Sends simulated IVAC SMS messages via ADB to test the Android companion app's
    BroadcastReceiver, OtpParser, deduplication cache, and backend relay forwarding.
#>

param(
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$DeviceSerial = ""
)

if (-not (Test-Path $AdbPath)) {
    $AdbPath = "adb"
}

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "📱 IVAC ASSISTANT MOBILE — ADB SMS TEST SIMULATOR" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# Check ADB devices
$devicesOutput = & $AdbPath devices
Write-Host $devicesOutput

$targetArgs = @()
if ($DeviceSerial -ne "") {
    $targetArgs = @("-s", $DeviceSerial)
}

function Send-SimulatedSms {
    param(
        [string]$Sender = "IVACBD",
        [string]$Message = ""
    )

    Write-Host "`n[+] Sending SMS from sender '$Sender':" -ForegroundColor Yellow
    Write-Host "    Message: $Message" -ForegroundColor Gray

    # Send broadcast intent mimicking SMS_RECEIVED
    # Note: On rooted devices or emulators, broadcast or emu can be used.
    # On non-rooted devices, 'adb emu sms send' works with emulators.
    $escapedMsg = $Message.Replace('"', '\"')
    
    # Try emu sms send first (if emulator)
    & $AdbPath @targetArgs emu sms send $Sender "$Message" 2>$null
    if ($LASTEXITCODE -ne 0) {
        # Fallback to standard broadcast intent
        Write-Host "    (Dispatching android.provider.Telephony.SMS_RECEIVED intent...)" -ForegroundColor DarkGray
        & $AdbPath @targetArgs shell am broadcast -a android.provider.Telephony.SMS_RECEIVED --es "pdus" "$escapedMsg" 2>$null
    }

    Write-Host "    ✓ Dispatched to device." -ForegroundColor Green
}

Write-Host "`nSelect a test case to execute:"
Write-Host "1. Test Standard 6-digit numeric OTP ('420318')"
Write-Host "2. Test Spoken Word-Form OTP ('Four-Two-Zero-Three-One-Eight')"
Write-Host "3. Test Bengali Digit OTP ('৪২০৩১৮')"
Write-Host "4. Test Unrelated SMS (Bank OTP - should be IGNORED)"
Write-Host "5. Test Duplicate Delivery Prevention (sends same OTP twice)"
Write-Host "6. Monitor Logcat for IVAC Assistant logs (sanitized)"
Write-Host "Q. Quit"

$choice = Read-Host "`nEnter selection (1-6, Q)"

switch ($choice) {
    "1" {
        Send-SimulatedSms -Sender "IVACBD" -Message "Your IVAC appointment login verification code is 420318. Valid for 5 minutes."
    }
    "2" {
        Send-SimulatedSms -Sender "IVAC" -Message "Dear Applicant, your IVAC portal verification code is Four-Two-Zero-Three-One-Eight. Do not share with anyone."
    }
    "3" {
        Send-SimulatedSms -Sender "BTCL" -Message "IVAC ভেরিফিকেশন কোড ৪২০৩১৮"
    }
    "4" {
        Send-SimulatedSms -Sender "CITYBANK" -Message "Your City Bank OTP is 987654 for transaction BDT 5000."
        Write-Host "    Expected: App should ignore this message completely." -ForegroundColor Green
    }
    "5" {
        Write-Host "Sending first copy..."
        Send-SimulatedSms -Sender "IVACBD" -Message "IVAC OTP: 778899"
        Start-Sleep -Seconds 1
        Write-Host "Sending duplicate copy within 1 second..."
        Send-SimulatedSms -Sender "IVACBD" -Message "IVAC OTP: 778899"
        Write-Host "    Expected: First is forwarded, second is caught by 120s deduplication cache." -ForegroundColor Green
    }
    "6" {
        Write-Host "Monitoring logcat (Ctrl+C to stop)..." -ForegroundColor Yellow
        & $AdbPath @targetArgs logcat -v time -s IvacSmsReceiver:V IvacBootReceiver:V IvacRepository:V
    }
    Default {
        Write-Host "Exiting."
    }
}

