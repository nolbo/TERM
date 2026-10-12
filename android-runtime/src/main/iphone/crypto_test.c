/* SPDX-License-Identifier: GPL-3.0-or-later
 * No device or persistent keys: test the real pairing crypto on Android.
 */
#include <string.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/entropy.h>
#include <mbedtls/pk.h>
#include <mbedtls/rsa.h>
#include "common/userpref.h"

int terminal_crypto_self_test(void)
{
    mbedtls_entropy_context entropy;
    mbedtls_ctr_drbg_context random;
    mbedtls_pk_context key;
    mbedtls_entropy_init(&entropy);
    mbedtls_ctr_drbg_init(&random);
    mbedtls_pk_init(&key);
    int result = -1;
    plist_t record = plist_new_dict();
    unsigned char public_key[2048];
    if (mbedtls_ctr_drbg_seed(&random, mbedtls_entropy_func, &entropy,
        (const unsigned char *)"terminal-test", 13)) goto done;
    if (mbedtls_pk_setup(&key, mbedtls_pk_info_from_type(MBEDTLS_PK_RSA))) goto done;
    if (mbedtls_rsa_gen_key(mbedtls_pk_rsa(key), mbedtls_ctr_drbg_random, &random, 2048, 65537)) goto done;
    if (mbedtls_pk_write_pubkey_pem(&key, public_key, sizeof(public_key))) goto done;
    key_data_t data = { public_key, (unsigned int)strlen((char *)public_key) + 1 };
    if (pair_record_generate_keys_and_certs(record, data, 0x110000) != USERPREF_E_SUCCESS) goto done;
    const char *required[] = { "RootCertificate", "HostCertificate", "DeviceCertificate", "RootPrivateKey", "HostPrivateKey" };
    for (int i = 0; i < 5; i++) {
        plist_t value = plist_dict_get_item(record, required[i]);
        uint64_t length = 0;
        if (!value || plist_get_node_type(value) != PLIST_DATA || !plist_get_data_ptr(value, &length) || !length) goto done;
    }
    result = 0;
done:
    plist_free(record);
    mbedtls_pk_free(&key);
    mbedtls_ctr_drbg_free(&random);
    mbedtls_entropy_free(&entropy);
    return result;
}
