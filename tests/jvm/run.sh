#!/usr/bin/env bash
# Testa, fora do Android, a validação do contrato v1 e as regras do modo mock.
# Requer o compilador Kotlin (kotlinc) e Java 17+. OrgJsonStandIn.kt imita o org.json do Android só para o teste.
set -euo pipefail
cd "$(dirname "$0")"
SRC=../../modules/citizen-assist/android/src/main/java/expo/modules/citizenassist
rm -rf out
kotlinc -nowarn OrgJsonStandIn.kt "$SRC/GuidanceContract.kt" "$SRC/GuidanceTransport.kt" ContractTest.kt -include-runtime -d out/tests.jar
java -jar out/tests.jar
