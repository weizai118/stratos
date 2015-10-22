# Apache Stratos Monitoring Dashboard

This directory contains following artifacts: <br />
(1) capps - Includes stratos-monitoring-service car file which bundles all Event Stream, Event receiver, Even Store, Gadgets, SparkScripts and Dashboard artifacts. <br />
(2) jaggery-files <br />

Follow the below steps to generate the monitoring dashboard: <br />
1. Add jaggery files which can be found in <Stratos-DAS-Distribution>/monitoring-dashboard/jaggery-files/ to DAS path '<DAS_HOME/repository/deployment/server/jaggeryapps/portal/controllers/apis/' <br/>
2. Create MySQL database and tables using queries in <Stratos-DAS-Distribution>/monitoring-dashboard/monitoring-mysqlscript.sql manually. <br />
3. Copy CEP  EventFormatter artifacts in <Stratos-DAS-Distribution>/wso2cep-<version>/eventformatters/ to <CEP-HOME>/repository/deployment/server/eventformatters/. <br />
4. Copy CEP OutputEventAdapter artifact in <Stratos-DAS-Distribution>/wso2cep-<version>/outputeventadaptors/ to <CEP-HOME>/repository/deployment/server/outputeventadaptors  and update the tcp and ssl ports according to DAS server port offset. <br />
5. Add stratos-monitoring-service car file in <Stratos-DAS-Distribution>/monitoring-dashboard/ to <DAS-HOME>/repository/deployment/server/carbonapps/ to generate the monitoring dashboard. <br />