#!/bin/bash
/home/vanilla/Desktop/AntiGravity/MusicRe/scratch/mydebugger shell am force-stop com.ytsync.app
/home/vanilla/Desktop/AntiGravity/MusicRe/scratch/mydebugger logcat -c
/home/vanilla/Desktop/AntiGravity/MusicRe/scratch/mydebugger shell am start -n com.ytsync.app/com.ytsync.app.MainActivity
sleep 8
/home/vanilla/Desktop/AntiGravity/MusicRe/scratch/mydebugger logcat -d > /home/vanilla/Desktop/AntiGravity/MusicRe/scratch/raw_logs.txt
