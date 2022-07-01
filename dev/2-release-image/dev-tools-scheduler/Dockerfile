FROM openjdk:11-oracle

ENV REFRESHED_AT 2022-07-01

RUN set -eux; \
    microdnf -y update; \
    mv /etc/localtime /etc/localtime.bak && ln -s /usr/share/zoneinfo/Europe/Warsaw /etc/localtime; \
    microdnf -y install zip unzip wget curl less nano bind-utils net-tools traceroute iproute lsof psmisc;

RUN set -eux; \
    curl -L https://www.scala-sbt.org/sbt-rpm.repo > sbt-rpm.repo && mv sbt-rpm.repo /etc/yum.repos.d/ && microdnf -y install sbt; \
    mkdir app; cd app;

WORKDIR /app

EXPOSE 9000
CMD ["sbt", "-Dsbt.boot.directory=/.sbt/", "-Dsbt.ivy.home=/.sbt/", "-Divy.home=/.sbt/", "-Dconfig.resource=scheduler.conf", "-J-Xms512M", "-J-Xmx1024M", "-J-Xss1M", "clean", "run 7001"]


