package org.voany.lib;

import android.app.Notification;
import android.content.pm.ServiceInfo;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.*;

public class CallNotificationTest {
    private VoAnyService service;
    private Object original;
    private static Field field(String name)throws Exception {Field f=VoAnyService.class.getDeclaredField(name);f.setAccessible(true);return f;}
    @Before public void setup()throws Exception {original=field("instance").get(null);service=mock(VoAnyService.class);field("instance").set(null,service);}
    @After public void cleanup()throws Exception {field("instance").set(null,original);}
    @Test public void callStyleUsesServiceNotificationIdAndPreservesMicrophoneType()throws Exception {
        int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE|ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL|ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        when(service.getForegroundServiceType()).thenReturn(type);Notification notification=mock(Notification.class);
        assertTrue(VoAnyService.showCallNotification(notification));verify(service).startForeground(201,notification,type);
        verify(service).getForegroundServiceType();
        assertSame(notification,field("callNotification").get(service));
        Method poll=VoAnyService.class.getDeclaredMethod("updateNotification",String.class,int.class);poll.setAccessible(true);poll.invoke(service,"Ready for calls",0);
        verifyNoMoreInteractions(service);
    }
    @Test public void ringingNotificationUsesExistingConnectionForegroundType(){
        when(service.getForegroundServiceType()).thenReturn(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);Notification notification=mock(Notification.class);
        assertTrue(VoAnyService.showCallNotification(notification));verify(service).startForeground(201,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }
    @Test public void serviceShutdownDoesNotPostAStandaloneCallStyle()throws Exception {
        field("stopping").setBoolean(service,true);assertFalse(VoAnyService.showCallNotification(mock(Notification.class)));verifyNoInteractions(service);
    }
}
