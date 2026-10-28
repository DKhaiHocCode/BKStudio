package com.bkstudio.notification.domain;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled (the outbox sender is the first job; reminders come later). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class NotificationSchedulingConfig {}
