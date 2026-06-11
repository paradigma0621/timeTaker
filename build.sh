#!/usr/bin/env bash
# Compila e empacota o TimeTaker para Java 8.
set -e

SRC_DIR="src/main/java"
OUT="out"

mkdir -p "$OUT"

echo "Compilando (alvo Java 8)..."
javac -encoding UTF-8 --release 8 -d "$OUT" $(find "$SRC_DIR" -name '*.java')

echo "Gerando timetaker.jar..."
jar cfe timetaker.jar com.timetaker.TimeTakerApp -C "$OUT" .

echo
echo "OK. Execute com:  java -jar timetaker.jar"
