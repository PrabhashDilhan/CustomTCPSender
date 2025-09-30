# Custom TCP sender for WSO2 MI which utilize the java NIO for non blocking IO operations
## Bulid the project using maven and add the jar file into the <MI_HOME>/lib <br />

## Add the following config to the deployment.toml <br />

```yaml
[transport.tcp]
listener.enable = true

[[custom_transport.sender]]
class="org.wso2.custom.transport.tcp.CustomTCPTransportSenderNIO"
protocol = "tcp"

```
## Need to define the backend endpoint as below and you can define the delimiter as well <br />

```xml
<endpoint>
    <address uri="tcp://localhost:8081?delimiter=0x0A&amp;delimiterType=byte"/>
</endpoint>
```