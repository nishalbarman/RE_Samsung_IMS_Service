@echo off
cd /d E:\Samsung_IMS\IMS\my-mod\ims-project
call gradlew.bat assembleDebug > build_output_log.txt 2>&1
echo DONE >> build_output_log.txt
