#!/bin/bash
# Copyright (c) 2019 & onwards. MapR Tech, Inc., All rights reserved

#This script creates the default key and trust stores used by MapR spark UI for
#various HTTPS connections. These are self signed.

CURRENT_USER=$(id -u -n)

set -x

MAPR_HOME=${MAPR_HOME:=/opt/mapr}
INSTALL_DIR=/home/$CURRENT_USER/__spark-internal__/security_keys
MAPRFS_DIR=/apps/spark/__$CURRENT_USER-spark-internal__/security_keys
BC_JAR="/opt/mapr/lib/bc-fips-1.0.2.1.jar"

sslKeyStore=${INSTALL_DIR}/ssl_keystore
sslKeyStoreP12=${INSTALL_DIR}/ssl_keystore.p12
sslKeyStorePEM=${INSTALL_DIR}/ssl_keystore.pem
sslKeyStoreBC=${INSTALL_DIR}/ssl_keystore.bcfks

sslTrustStore=${INSTALL_DIR}/ssl_truststore
sslTrustStoreP12=${INSTALL_DIR}/ssl_truststore.p12
sslTrustStorePEM=${INSTALL_DIR}/ssl_truststore.pem
sslTrustStoreBC=${INSTALL_DIR}/ssl_truststore.bcfks

if [ -f /proc/sys/crypto/fips_enabled ]; then
  FIPS_ENABLED=$(cat /proc/sys/crypto/fips_enabled)
else
  FIPS_ENABLED="0"
fi

if [ "$FIPS_ENABLED" = "1" ]; then
  isFips="true"
  FIPS_STORE_PARAMS="-provider org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider -providerpath ${BC_JAR} -providername BCFIPS"
else
  isFips="false"
  FIPS_STORE_PARAMS=""
fi

if [ "$isFips" == "true" ]; then
  sslKeyStore=${sslKeyStoreBC}
  sslTrustStore=${sslTrustStoreBC}
else
  sslKeyStore=${sslKeyStore}
  sslTrustStore=${sslTrustStore}
fi

storePass=$1
storeFormatPKCS12=pkcs12

if [ "$isFips" == "true" ]; then
  storeFormat=bcfks
  srcType=bcfks
else
  storeFormat=JKS
  srcType=JKS
fi

expireInDays="36500"
convertingKeystore=false
noPem=0
dstType=pkcs12
#VERBOSE="-v"
clusterConf=${MAPR_HOME:=/opt/mapr}/conf/mapr-clusters.conf

. ${MAPR_HOME:=/opt/mapr}/server/scripts-common.sh
if [ "$JAVA_HOME"x = "x" ]; then
  KEYTOOL=$(which keytool)
else
  KEYTOOL=$JAVA_HOME/bin/keytool
fi

# Check if keytool is actually valid and exists
if [ ! -e "${KEYTOOL:-}" ]; then
  echo "The keytool in \"${KEYTOOL}\" does not exist."
  echo "Keytool not found or JAVA_HOME not set properly. Please install keytool or set JAVA_HOME properly."
  exit 1
fi

CLUSTERCONF_FIRST_LINE=$(head -n 1 $clusterConf)
ARR=($CLUSTERCONF_FIRST_LINE)
CLUSTERNAME=${ARR[0]}

#discover DNS domain for host unless provided on command line and use
#in certificate DN
DOMAINNAME=$(hostname -d)
if [ "$DOMAINNAME"x = "x" ]; then
  CERTNAME=$(cat ${MAPR_HOME:=/opt/mapr}/hostname)
else
  CERTNAME="*."$DOMAINNAME
fi

function confirmNotThere() {
  if [ -f ${sslKeyStore} ]; then
    exit 0
  fi
}

function confirmNotThere() {
  if [ -f ${sslKeyStore} ]; then
    keystore_check_cmd='keytool --list -keystore $sslKeyStore -storepass $storePass'
    checkKeyResult=$(eval $keystore_check_cmd)
    errorSubstr="Keystore was tampered with, or password was incorrect"
    if ! [[ $checkKeyResult =~ $errorSubstr ]]; then
      exit 0
    fi
    rm -rf /home/$USER/__spark-internal__
    mkdir -p ${INSTALL_DIR}
  fi
}

function createDirs() {
  mkdir -p ${INSTALL_DIR}
  hadoop fs -mkdir -p ${MAPRFS_DIR}
}

function copyToMfs() {
  hadoop fs -copyFromLocal -f ${INSTALL_DIR}/* ${MAPRFS_DIR}
}

function createCertificates() {
  #create self signed certificate with private key

  echo "Creating 100 year self signed certificate with subjectDN='CN=$CERTNAME'"
  $KEYTOOL -genkeypair -sigalg SHA512withRSA -keyalg RSA -alias $CLUSTERNAME -dname CN=$CERTNAME -validity $expireInDays \
    -storepass $storePass -keypass $storePass \
    -keystore $sslKeyStore -storetype $storeFormat $VERBOSE $FIPS_STORE_PARAMS
  if [ $? -ne 0 ]; then
    echo "Keytool command to generate key store failed"
  else
    echo "Keytool command to generate key store succeed"
  fi

  #extract self signed certificate into trust store
  tfile=/tmp/tmpfile-mapcert.$$
  /bin/rm -f $tfile
  $KEYTOOL -exportcert -keystore $sslKeyStore -file $tfile \
    -alias $CLUSTERNAME -storepass $storePass -storetype $storeFormat $VERBOSE $FIPS_STORE_PARAMS
  if [ $? -ne 0 ]; then
    echo "Keytool command to extract certificate from key store failed"
  else
    echo "Keytool command to extract certificate from key store succeed"
  fi
  $KEYTOOL -importcert -keystore $sslTrustStore -file $tfile \
    -alias $CLUSTERNAME -storepass $storePass -storetype $storeFormat -noprompt $VERBOSE $FIPS_STORE_PARAMS
  if [ $? -ne 0 ]; then
    echo "Keytool command to create trust store failed"
  else
    echo "Keytool command to create trust store succeed"
  fi
  #create PEM version
  convertToPem $sslKeyStore $sslKeyStorePEM true true
  convertToPem $sslTrustStore $sslTrustStorePEM true
  /bin/rm -f $tfile
}

function setPermissions() {
  #set permissions on key and trust store
  MAPR_UG="$CURRENT_USER:$CURRENT_USER"

  chown $MAPR_UG ${INSTALL_DIR}/*
  chmod 600 ${INSTALL_DIR}/ssl_keystore*
  chmod 644 ${INSTALL_DIR}/ssl_truststore*

  hadoop fs -chown $MAPR_UG ${MAPRFS_DIR}/*
  hadoop fs -chmod 600 ${MAPRFS_DIR}/ssl_keystore*
  hadoop fs -chmod 644 ${MAPRFS_DIR}/ssl_truststore*

    if [ "$isFips" == "true" ]; then
      if [ ! -e ${INSTALL_DIR}/ssl_keystore ]; then
        if [ ! -e ${INSTALL_DIR}/ssl_keystore.bcfks ]; then
          echo "${INSTALL_DIR}/ssl_keystore.bcfks not found, cannot create symlink"
          exit 1
        fi
        ln -sf ${INSTALL_DIR}/ssl_keystore.bcfks ${INSTALL_DIR}/ssl_keystore
        chown $MAPR_UG ${INSTALL_DIR}/ssl_keystore
      fi

      if [ ! -e ${INSTALL_DIR}/ssl_truststore ]; then
        if [ ! -e ${INSTALL_DIR}/ssl_truststore.bcfks ]; then
          echo "${INSTALL_DIR}/ssl_truststore.bcfks not found, cannot create symlink"
          exit 1
        fi
        ln -sf ${INSTALL_DIR}/ssl_truststore.bcfks ${INSTALL_DIR}/ssl_truststore
        chown $MAPR_UG ${INSTALL_DIR}/ssl_truststore
      fi
    fi
}

function convertToPem() {
  from=$1
  to=$2
  silent=$3
  useKSPwForPem=$4
  passwd=$5
  base_from=$(basename $from)
  base_to=$(basename $to)
  base_from_without_ext="$(echo $base_from | cut -d'.' -f1)"
  base_to_without_ext="$(echo $base_to | cut -d'.' -f1)"
  dir_from=$(dirname $from)
  dir_to=$(dirname $to)
  passout=""
  fExt=""
  case "$dstType" in
  JKS) fExt="jks" ;;
  pkcs12) fExt="p12" ;;
  esac
  if [ "$useKSPwForPem" == "true" ]; then
    passout="-passout pass:$storePass"
  elif [ -n "$passwd" ]; then
    passout="-passout pass:$passwd"
  fi
  destSSLStore="$dir_to/${base_to_without_ext}.$fExt"
  if [ -z "$silent" ]; then
    echo "Converting certificates from $from into $to"
    if [ ! -f "$from" ]; then
      echo "Source trust store not found: $from"
      exit 1
    fi
    if [ -f "$to" ]; then
      echo "Destination trust store already exists: $to"
      exit 1
    fi
    if [ "$to" == "$from" ]; then
      echo "Source trust store cannot be the same as Destination trust store"
      exit 1
    fi
  fi

  if [ "$isFips" == "true" ]; then
    destStoreType=$dstType
    if [ "$dstType" = "pkcs12" ]; then
      destStoreType="bcpkcs12"
    fi
    $KEYTOOL -importkeystore -srckeystore $from -destkeystore $destSSLStore \
      -srcstorepass $storePass -deststorepass $storePass -srcalias $CLUSTERNAME \
      -srcstoretype $srcType -deststoretype $destStoreType -noprompt \
      $FIPS_STORE_PARAMS >/dev/null

    openssl $storeFormatPKCS12 -info -in $destSSLStore \
      -passin pass:$storePass -passout pass:$storePass >/dev/null 2>&1
  else
    $KEYTOOL -importkeystore -srckeystore $from -destkeystore $destSSLStore \
      -srcstorepass $storePass -deststorepass $storePass -srcalias $CLUSTERNAME \
      -srcstoretype $srcType -deststoretype $dstType -noprompt $VERBOSE
  fi

  if [ $? -ne 0 ]; then
    echo "Keytool command to create $dstType trust/key store failed"
    [ -z "$slient" ] && exit 1
  fi
  if [ "$dstType" = "pkcs12" ] && [ "$noPem" -eq 0 ]; then
    if [ "$to" = "$destSSLStore" ]; then
      # someone is converting to pkcs12 and using p12 as the extension
      # on the "to" file without realizing we will generating a pem file
      # too - fix it
      to="${to/.p12/}.pem"
    fi
    openssl $storeFormatPKCS12 -in $destSSLStore -out $to -passin pass:$storePass $passout
    if [ $? -ne 0 ]; then
      echo "openssl command to create PEM trust store failed"
    fi
  fi
}

################
#  main        #
################
createDirs
confirmNotThere
createCertificates
copyToMfs
setPermissions