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