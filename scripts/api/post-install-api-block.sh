#!/bin/bash

# Run this script post-installation, to block all the settings that 
# should not be available to the general public in a production Dataverse installation.

# First, revoke the authentication token from the built-in user: 
curl -X DELETE $SERVER/admin/settings/BuiltinUsers.KEY

# Block the sensitive endpoints:
# Relevant settings:
#  - :BlockedApiPolicy - one of allow, drop, localhost-only, unblock-key
#  - :BlockedApiKey - when using the unblock-key policy, pass this key in the unblock-key query param to allow the call to a blocked endpoint
#  - :BlockedApiEndpoints - comma separated list of blocked api endpoints

# This leaves /api/admin and /api/test blocked to all connections except from those 
# coming from localhost:
curl -X PUT -d localhost-only http://localhost:8080/api/admin/settings/:BlockedApiPolicy
curl -X PUT -d admin,test http://localhost:8080/api/admin/settings/:BlockedApiEndpoints

# In some situations, you may prefer an alternative solution - to block ALL connections to 
# these endpoints completely; but allow connections authenticated with the defined 
# "unblock key" (password): 

#curl -X PUT -d YOURSUPERSECRETUNBLOCKKEY http://localhost:8080/api/admin/settings/:BlockedApiKey 
#curl -X PUT -d localhost-only http://localhost:8080/api/admin/settings/:BlockedApiPolicy


