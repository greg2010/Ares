FROM openjdk:8-jre-alpine

RUN mkdir /red
WORKDIR /red

COPY ./target/uberjar/ares-standalone.jar ares.jar

ADD ares.sv.conf /etc/supervisor/conf.d/

RUN apk update
RUN apk add supervisor

CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/ares.sv.conf"]