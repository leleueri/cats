package io.argos.agent.notifiers

import java.util.{Date, Properties}
import javax.mail._
import javax.mail.internet.{InternetAddress, MimeMessage}

import akka.actor.{Actor, ActorLogging, Props}
import com.typesafe.config.ConfigFactory
import io.argos.agent.Constants
import io.argos.agent.bean.Notification
import Constants._

import scala.collection.JavaConverters._

class MailNotifier extends Notifier {

  val configMail = notifierConfig

  val to = configMail.getStringList(CONF_MAIL_NOTIFIER_RECIPIENTS).asScala
  val from = configMail.getString(CONF_MAIL_NOTIFIER_FROM)
  val host = configMail.getString(CONF_MAIL_NOTIFIER_SMTP)
  val port = if (configMail.hasPath(CONF_MAIL_NOTIFIER_SMTP_PORT)) configMail.getString(CONF_MAIL_NOTIFIER_SMTP_PORT) else "25"

  val props = new Properties();
  props.put("mail.smtp.host", host);
  props.put("mail.smtp.port", port);
  val session = Session.getInstance(props, null);

  log.info("MailNotifier is running...")

  /**
   * The notifier identifier used a key in the 'notifiers' section of the configuration.
   * The value provided by this method is used to locate the entry of the provider configuration
   * into the "cats.notifiers" section.
   *
   * @return
   */
  override def notifierId: String = "mail"

  override def onNotification(notif: Notification) = {
    sendMessage(notif.title, notif.message)
  }

  def sendMessage(title: String, msg: String) : Unit = {
    try {

      log.debug("Send Message with title : {}", title)

      val message = new MimeMessage(session);
      message.setFrom(new InternetAddress(from));
      to.foreach {
        x => message.addRecipient(Message.RecipientType.TO, new InternetAddress(x))
      }
      message.setSentDate(new Date());
      message.setSubject(title);
      message.setText(msg);

      Transport.send(message);
    } catch {
      case mex : Exception =>  log.warning("Unable to send the notification message : {}", mex.getMessage, mex)
    }
  }
}


class MailNotifierProvider extends NotifierProvider {
  def actorName() : String = "MailNotifier"
  def props(): Props = Props[MailNotifier]
}