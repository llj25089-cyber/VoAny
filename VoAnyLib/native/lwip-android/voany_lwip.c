#include "voany_lwip.h"
#include <jni.h>
#include <pthread.h>
#include <string.h>
#include "lwip/tcpip.h"
#include "lwip/netif.h"
#include "lwip/sockets.h"
#include "lwip/inet.h"
static struct netif interface;
static pthread_mutex_t state_lock=PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t ready_cond=PTHREAD_COND_INITIALIZER;
static bool initialized,ready,active;
static voany_output_fn output_fn;
static JavaVM *vm;
static jclass java_bridge;
static jmethodID transform;
static void ready_cb(void *arg){pthread_mutex_lock(&state_lock);ready=true;pthread_cond_signal(&ready_cond);pthread_mutex_unlock(&state_lock);}
static JNIEnv *environment(bool *attached){JNIEnv *env=NULL;*attached=false;if(!vm)return NULL;if((*vm)->GetEnv(vm,(void**)&env,JNI_VERSION_1_6)!=JNI_OK){if((*vm)->AttachCurrentThread(vm,&env,NULL)!=JNI_OK)return NULL;*attached=true;}return env;}
static unsigned char *process(const void *bytes,size_t len,bool inbound,size_t *result_len){
 bool attached;JNIEnv *env=environment(&attached);if(!env || !java_bridge){if(attached)(*vm)->DetachCurrentThread(vm);return NULL;}
 jbyteArray input=(*env)->NewByteArray(env,len);if(!input){if(attached)(*vm)->DetachCurrentThread(vm);return NULL;}
 (*env)->SetByteArrayRegion(env,input,0,len,bytes);
 jbyteArray result=(*env)->CallStaticObjectMethod(env,java_bridge,transform,input,inbound);
 unsigned char *out=NULL;
 if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);}else if(result){*result_len=(*env)->GetArrayLength(env,result);out=malloc(*result_len);if(out)(*env)->GetByteArrayRegion(env,result,0,*result_len,(jbyte*)out);}
 if(result)(*env)->DeleteLocalRef(env,result);(*env)->DeleteLocalRef(env,input);if(attached)(*vm)->DetachCurrentThread(vm);return out;
}
static err_t output_packet(struct netif *n,struct pbuf *p){
 unsigned char *data=malloc(p->tot_len);if(!data)return ERR_MEM;pbuf_copy_partial(p,data,p->tot_len,0);
 size_t len=0;unsigned char *encoded=process(data,p->tot_len,false,&len);free(data);
 pthread_mutex_lock(&state_lock);if(encoded && active && output_fn)output_fn(encoded,len);pthread_mutex_unlock(&state_lock);free(encoded);return ERR_OK;
}
static err_t output4(struct netif *n,struct pbuf *p,const ip4_addr_t *d){return output_packet(n,p);}
static err_t output6(struct netif *n,struct pbuf *p,const ip6_addr_t *d){return output_packet(n,p);}
static err_t netif_init_cb(struct netif *n){n->name[0]='v';n->name[1]='a';n->output=output4;n->output_ip6=output6;return ERR_OK;}
JNIEXPORT void JNICALL Java_org_voany_android_net_Lwip_init(JNIEnv *env,jclass c){
 pthread_mutex_lock(&state_lock);if(!vm){(*env)->GetJavaVM(env,&vm);java_bridge=(*env)->NewGlobalRef(env,c);transform=(*env)->GetStaticMethodID(env,c,"transform","([BZ)[B");}
 if(!initialized){initialized=true;pthread_mutex_unlock(&state_lock);tcpip_init(ready_cb,NULL);pthread_mutex_lock(&state_lock);}
 while(!ready)pthread_cond_wait(&ready_cond,&state_lock);pthread_mutex_unlock(&state_lock);
}
bool voany_lwip_start(const void *ipv4,const void *ipv6,int mtu,voany_output_fn output){
 if(!ready || (!ipv4 && !ipv6) || (ipv6 && mtu<1280))return false;
 LOCK_TCPIP_CORE();pthread_mutex_lock(&state_lock);bool previous=active;active=false;pthread_mutex_unlock(&state_lock);if(previous){netif_set_down(&interface);netif_remove(&interface);}
 ip4_addr_t ip={0},mask={0},gw={0};if(ipv4){memcpy(&ip.addr,ipv4,4);mask.addr=0xffffffff;}
 if(!netif_add(&interface,&ip,&mask,&gw,NULL,netif_init_cb,tcpip_input)){UNLOCK_TCPIP_CORE();return false;}
 if(ipv6){ip6_addr_t ip6;memcpy(ip6.addr,ipv6,16);ip6_addr_clear_zone(&ip6);netif_ip6_addr_set(&interface,0,&ip6);netif_ip6_addr_set_state(&interface,0,IP6_ADDR_PREFERRED);}
 interface.mtu=mtu;pthread_mutex_lock(&state_lock);output_fn=output;active=true;pthread_mutex_unlock(&state_lock);netif_set_default(&interface);netif_set_up(&interface);netif_set_link_up(&interface);
 UNLOCK_TCPIP_CORE();return true;
}
void voany_lwip_stop(void){if(!ready)return;LOCK_TCPIP_CORE();pthread_mutex_lock(&state_lock);bool previous=active;active=false;output_fn=NULL;pthread_mutex_unlock(&state_lock);if(previous){netif_set_down(&interface);netif_remove(&interface);}UNLOCK_TCPIP_CORE();}
void voany_lwip_input(const void *bytes,size_t size){size_t len=0;unsigned char *plain=process(bytes,size,true,&len);if(!plain || len>65535){free(plain);return;}
 pthread_mutex_lock(&state_lock);if(active){struct pbuf *p=pbuf_alloc(PBUF_RAW,len,PBUF_RAM);if(p){pbuf_take(p,plain,len);if(tcpip_input(p,&interface)!=ERR_OK)pbuf_free(p);}}pthread_mutex_unlock(&state_lock);free(plain);}
static void error(JNIEnv *env,const char *op){char message[160];snprintf(message,sizeof(message),"lwIP %s: %s (%d)",op,strerror(errno),errno);(*env)->ThrowNew(env,(*env)->FindClass(env,(errno==EWOULDBLOCK || errno==EAGAIN) ? "java/net/SocketTimeoutException" : "java/io/IOException"),message);}
static bool address(JNIEnv *env,jbyteArray bytes,int port,struct sockaddr_storage *out,socklen_t *len){
 memset(out,0,sizeof(*out));int size=(*env)->GetArrayLength(env,bytes);
 if(size==4){struct sockaddr_in *a=(void*)out;a->sin_family=AF_INET;a->sin_port=lwip_htons(port);(*env)->GetByteArrayRegion(env,bytes,0,4,(void*)&a->sin_addr);*len=sizeof(*a);return true;}
 if(size==16){struct sockaddr_in6 *a=(void*)out;a->sin6_family=AF_INET6;a->sin6_port=lwip_htons(port);(*env)->GetByteArrayRegion(env,bytes,0,16,(void*)&a->sin6_addr);*len=sizeof(*a);return true;}
 (*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),"Invalid lwIP address");return false;
}
JNIEXPORT jint JNICALL Java_org_voany_android_net_Lwip_socket(JNIEnv *env,jclass c,jboolean ipv6,jboolean tcp){int fd=lwip_socket(ipv6 ? AF_INET6 : AF_INET,tcp ? SOCK_STREAM : SOCK_DGRAM,0);if(fd<0)error(env,"socket");else{int reuse=1;lwip_setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&reuse,sizeof(reuse));struct timeval t={10,0};lwip_setsockopt(fd,SOL_SOCKET,SO_SNDTIMEO,&t,sizeof(t));}return fd;}
JNIEXPORT void JNICALL Java_org_voany_android_net_Lwip_bind(JNIEnv *env,jclass c,jint fd,jbyteArray ip,jint port){struct sockaddr_storage a;socklen_t l;if(address(env,ip,port,&a,&l) && lwip_bind(fd,(void*)&a,l)<0)error(env,"bind");}
JNIEXPORT jint JNICALL Java_org_voany_android_net_Lwip_port(JNIEnv *env,jclass c,jint fd){struct sockaddr_storage a;socklen_t l=sizeof(a);if(lwip_getsockname(fd,(void*)&a,&l)<0){error(env,"getsockname");return 0;}return lwip_ntohs(a.ss_family==AF_INET ? ((struct sockaddr_in*)&a)->sin_port : ((struct sockaddr_in6*)&a)->sin6_port);}
JNIEXPORT void JNICALL Java_org_voany_android_net_Lwip_connect(JNIEnv *env,jclass c,jint fd,jbyteArray ip,jint port){
 struct sockaddr_storage a;socklen_t l;if(!address(env,ip,port,&a,&l))return;
 int flags=lwip_fcntl(fd,F_GETFL,0);lwip_fcntl(fd,F_SETFL,flags|O_NONBLOCK);
 int result=lwip_connect(fd,(void*)&a,l),failure=errno;
 if(result<0 && failure==EINPROGRESS){
   fd_set writes;FD_ZERO(&writes);FD_SET(fd,&writes);struct timeval timeout={10,0};
   result=lwip_select(fd+1,NULL,&writes,NULL,&timeout);
   if(result>0){int socket_error=0;socklen_t len=sizeof(socket_error);if(lwip_getsockopt(fd,SOL_SOCKET,SO_ERROR,&socket_error,&len)<0)failure=errno;else failure=socket_error;result=failure ? -1 : 0;}
   else{failure=result==0 ? ETIMEDOUT : errno;result=-1;}
 }
 lwip_fcntl(fd,F_SETFL,flags);if(result<0){errno=failure;error(env,"connect");}
}
JNIEXPORT void JNICALL Java_org_voany_android_net_Lwip_timeout(JNIEnv *env,jclass c,jint fd,jint ms){struct timeval t={ms/1000,(ms%1000)*1000};if(lwip_setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&t,sizeof(t))<0)error(env,"timeout");}
JNIEXPORT void JNICALL Java_org_voany_android_net_Lwip_send(JNIEnv *env,jclass c,jint fd,jbyteArray bytes,jbyteArray ip,jint port){
 jsize size=(*env)->GetArrayLength(env,bytes);jbyte *data=(*env)->GetByteArrayElements(env,bytes,NULL);int result;
 if(ip){struct sockaddr_storage a;socklen_t l;result=address(env,ip,port,&a,&l) ? lwip_sendto(fd,data,size,0,(void*)&a,l) : -1;}else{int sent=0;result=0;while(sent<size){int n=lwip_send(fd,data+sent,size-sent,0);if(n<=0){result=-1;break;}sent+=n;}if(result==0)result=sent;}
 (*env)->ReleaseByteArrayElements(env,bytes,data,JNI_ABORT);if(result!=size && !(*env)->ExceptionCheck(env))error(env,"send");
}
JNIEXPORT jbyteArray JNICALL Java_org_voany_android_net_Lwip_recv(JNIEnv *env,jclass c,jint fd,jint max){
 if(max<1 || max>65535)return NULL;unsigned char *data=malloc(max);if(!data){errno=ENOMEM;error(env,"recv");return NULL;}int size=lwip_recv(fd,data,max,0);jbyteArray result=NULL;
 if(size<0)error(env,"recv");else{result=(*env)->NewByteArray(env,size);if(result)(*env)->SetByteArrayRegion(env,result,0,size,(void*)data);}free(data);return result;
}
JNIEXPORT void JNICALL Java_org_voany_android_net_Lwip_close(JNIEnv *env,jclass c,jint fd){lwip_shutdown(fd,SHUT_RDWR);lwip_close(fd);}

JNIEXPORT void JNICALL Java_org_voany_android_net_Lwip_listen(JNIEnv *env,jclass c,jint fd){if(lwip_listen(fd,4)<0)error(env,"listen");}
JNIEXPORT jint JNICALL Java_org_voany_android_net_Lwip_accept(JNIEnv *env,jclass c,jint fd){int next=lwip_accept(fd,NULL,NULL);if(next<0)error(env,"accept");return next;}
JNIEXPORT jboolean JNICALL Java_org_voany_android_net_Lwip_peerMatches(JNIEnv *env,jclass c,jint fd,jbyteArray ip,jint port){
 struct sockaddr_storage actual,wanted;socklen_t actual_len=sizeof(actual),wanted_len;
 if(!address(env,ip,port,&wanted,&wanted_len))return false;
 if(lwip_getpeername(fd,(struct sockaddr*)&actual,&actual_len)<0){error(env,"getpeername");return false;}
 if(actual.ss_family!=wanted.ss_family)return false;
 if(actual.ss_family==AF_INET){struct sockaddr_in *a=(void*)&actual,*b=(void*)&wanted;return a->sin_port==b->sin_port && !memcmp(&a->sin_addr,&b->sin_addr,4);}
 struct sockaddr_in6 *a=(void*)&actual,*b=(void*)&wanted;return a->sin6_port==b->sin6_port && !memcmp(&a->sin6_addr,&b->sin6_addr,16);
}
