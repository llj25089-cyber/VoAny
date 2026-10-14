#ifndef VOANY_SIM_H
#define VOANY_SIM_H
#include <jni.h>
#include <networking/host.h>
void voany_sim_init(JNIEnv *env);
bool voany_sim_register(bool reg);
void voany_sim_deinit(JNIEnv *env);
void voany_report_address(host_t *host, bool pcscf);
void voany_report_mobility(bool supported, bool response);
void voany_reset_inner_addresses(void);
#endif
