#!/bin/bash

exit_if_fail () {
if [ $? -ne 0 ]
then
printf "test failure!\n"
exit 1
fi
}

lein clean
exit_if_fail

AWS_PROFILE=sredev lein release
