#!/usr/bin/env bash
# Compila e empacota o TimeTaker para Java 8.
set -e

SRC="src/main/java/com/timetaker/TimeTakerApp.java"
OUT="out"

mkdir -p "$OUT"

echo "Compilando (alvo Java 8)..."
javac -encoding UTF-8 --release 8 -d "$OUT" "$SRC"

echo "Gerando timetaker.jar..."
jar cfe timetaker.jar com.timetaker.TimeTakerApp -C "$OUT" .

echo
echo "OK. Execute com:  java -jar timetaker.jar"
