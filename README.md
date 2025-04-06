# ABOUT
This client automatically adds all needed commands and creates local SQLite database file containing all previously added channels \
You may also specify message to send on schedule or immediately 

***To send message to channel, you need to add your bot as admin to required channel and add channel id to bot***

### TO RUN WITH YOUR API KEY:

**_Java 24_ and _Maven_ need to be installed**

Command to build from source:
```shell
mvn clean package
```

Command to run (need to run in **_/target_** directory):
```shell
java -jar punlisher-bot-1.0-SNAPSHOT.jar <YOUR-API-KEY>
```
