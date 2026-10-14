#include "voany_sim.h"
#include "../android_jni.h"
#include <simaka_card.h>
#include <simaka_manager.h>
#include <threading/mutex.h>

static jclass bridge;
static jmethodID authenticate_method, pcscf_method, inner_method, mobility_method, inner_reset_method;
static mutex_t *aka_lock;
static char cached_rand[16], cached_auts[14];
static bool has_auts;

METHOD(simaka_card_t, get_quintuplet, status_t,
    simaka_card_t *this, identification_t *id, char rand[16], char autn[16],
    char ck[16], char ik[16], char res[AKA_RES_MAX], int *res_len)
{
    JNIEnv *env;
    jbyteArray jr, ja, response;
    chunk_t data = chunk_empty;
    status_t status = FAILED;
    int len;

    aka_lock->lock(aka_lock);
    has_auts = FALSE;
    memwipe(cached_auts, sizeof(cached_auts));
    memwipe(cached_rand, sizeof(cached_rand));
    androidjni_attach_thread(&env);
    jr = byte_array_from_chunk(env, chunk_create(rand, 16));
    ja = byte_array_from_chunk(env, chunk_create(autn, 16));
    response = (*env)->CallStaticObjectMethod(env, bridge, authenticate_method, jr, ja);
    if ((*env)->ExceptionCheck(env))
    {
        (*env)->ExceptionClear(env);
        response = NULL;
    }
    if (response)
    {
        data = chunk_from_byte_array(env, response);
        if (data.len == 16 && data.ptr[0] == 0xdc && data.ptr[1] == 14)
        {
            memcpy(cached_rand, rand, 16);
            memcpy(cached_auts, data.ptr + 2, 14);
            has_auts = TRUE;
            status = INVALID_STATE;
        }
        else if (data.len >= 40 && data.ptr[0] == 0xdb)
        {
            len = data.ptr[1];
            if (len >= 4 && len <= AKA_RES_MAX && data.len == 36 + len &&
                data.ptr[2 + len] == 16 && data.ptr[19 + len] == 16)
            {
                memcpy(res, data.ptr + 2, len);
                memcpy(ck, data.ptr + 3 + len, 16);
                memcpy(ik, data.ptr + 20 + len, 16);
                *res_len = len;
                status = SUCCESS;
            }
        }
        memwipe(data.ptr, data.len);
        (*env)->SetByteArrayRegion(env, response, 0, data.len, data.ptr);
        chunk_clear(&data);
        (*env)->DeleteLocalRef(env, response);
    }
    (*env)->DeleteLocalRef(env, jr);
    (*env)->DeleteLocalRef(env, ja);
    androidjni_detach_thread();
    aka_lock->unlock(aka_lock);
    return status;
}

METHOD(simaka_card_t, resync, bool,
    simaka_card_t *this, identification_t *id, char rand[16], char auts[14])
{
    bool success;
    aka_lock->lock(aka_lock);
    success = has_auts && memeq_const(rand, cached_rand, 16);
    if (success) memcpy(auts, cached_auts, 14);
    has_auts = FALSE;
    memwipe(cached_auts, 14); memwipe(cached_rand, 16);
    aka_lock->unlock(aka_lock);
    return success;
}

static void set_pseudonym(simaka_card_t *this, identification_t *id, identification_t *pseudonym) {}
static void set_reauth(simaka_card_t *this, identification_t *id, identification_t *next,
                       char mk[HASH_SIZE_SHA1], uint16_t counter) {}

static simaka_card_t card;

void voany_sim_init(JNIEnv *env)
{
    card = (simaka_card_t){
    .get_triplet = (void*)return_false,
    .get_quintuplet = _get_quintuplet,
    .resync = _resync,
    .set_pseudonym = set_pseudonym,
    .get_pseudonym = (void*)return_null,
    .set_reauth = set_reauth,
    .get_reauth = (void*)return_null,
    };
    jclass local = (*env)->FindClass(env, "org/voany/android/SimBridge");
    bridge = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    authenticate_method = (*env)->GetStaticMethodID(env, bridge, "authenticate", "([B[B)[B");
    pcscf_method = (*env)->GetStaticMethodID(env, bridge, "onPcscf", "(Ljava/lang/String;)V");
    inner_method = (*env)->GetStaticMethodID(env, bridge, "onInnerAddress", "(Ljava/lang/String;)V");
    mobility_method = (*env)->GetStaticMethodID(env, bridge, "onIkeMobility", "(ZZ)V");
    inner_reset_method = (*env)->GetStaticMethodID(env, bridge, "beginInnerAddresses", "()V");
    aka_lock = mutex_create(MUTEX_TYPE_DEFAULT);
}

bool voany_sim_register(bool reg)
{
    simaka_manager_t *manager = lib->get(lib, "aka-manager");
    if (!manager) return FALSE;
    if (reg) manager->add_card(manager, &card);
    else manager->remove_card(manager, &card);
    return TRUE;
}

void voany_sim_deinit(JNIEnv *env)
{
    memwipe(cached_rand, 16); memwipe(cached_auts, 14); has_auts = FALSE;
    aka_lock->destroy(aka_lock);
    (*env)->DeleteGlobalRef(env, bridge); bridge = NULL;
}

void voany_report_address(host_t *host, bool pcscf)
{
    JNIEnv *env;
    char address[INET6_ADDRSTRLEN];
    chunk_t raw = host->get_address(host);
    jstring value;
    if (!inet_ntop(host->get_family(host), raw.ptr, address, sizeof(address))) return;
    androidjni_attach_thread(&env);
    value = (*env)->NewStringUTF(env, address);
    (*env)->CallStaticVoidMethod(env, bridge, pcscf ? pcscf_method : inner_method, value);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, value);
    androidjni_detach_thread();
}

void voany_report_mobility(bool supported, bool response)
{
    JNIEnv *env;
    androidjni_attach_thread(&env);
    (*env)->CallStaticVoidMethod(env, bridge, mobility_method, supported, response);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    androidjni_detach_thread();
}

void voany_reset_inner_addresses(void)
{
    JNIEnv *env;
    androidjni_attach_thread(&env);
    (*env)->CallStaticVoidMethod(env, bridge, inner_reset_method);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    androidjni_detach_thread();
}
