/*
 * TerminalPane.java
 *
 * Copyright (C) 2009-16 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

package org.rstudio.studio.client.workbench.views.terminal;

import org.rstudio.core.client.CommandWithArg;
import org.rstudio.core.client.HandlerRegistrations;
import org.rstudio.studio.client.application.Desktop;
import org.rstudio.studio.client.common.SimpleRequestCallback;
import org.rstudio.studio.client.common.console.ConsoleOutputEvent;
import org.rstudio.studio.client.common.console.ConsoleProcess;
import org.rstudio.studio.client.common.console.ConsoleProcessInfo;
import org.rstudio.studio.client.common.console.ProcessExitEvent;
import org.rstudio.studio.client.common.crypto.CryptoServerOperations;
import org.rstudio.studio.client.common.crypto.PublicKeyInfo;
import org.rstudio.studio.client.common.crypto.RSAEncrypt;
import org.rstudio.studio.client.common.shell.ShellInput;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.server.Void;
import org.rstudio.studio.client.server.VoidServerRequestCallback;
import org.rstudio.studio.client.workbench.model.WorkbenchServerOperations;
import org.rstudio.studio.client.workbench.ui.WorkbenchPane;
import org.rstudio.studio.client.workbench.views.terminal.events.ResizeTerminalEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalDataInputEvent;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.ui.ResizeLayoutPanel;
import com.google.gwt.user.client.ui.Widget;

public class TerminalPane extends WorkbenchPane
                          implements ConsoleOutputEvent.Handler, 
                                     ProcessExitEvent.Handler,
                                     ClickHandler,
                                     ResizeTerminalEvent.Handler,
                                     TerminalDataInputEvent.Handler
{
   protected TerminalPane(String title, WorkbenchServerOperations server)
   {
      super(title);
      server_ = server;
      host_ = new ResizeLayoutPanel();
   }

   @Override
   protected Widget createMainWidget()
   {
      XTermWidget.load(new Command()
      {
         @Override
         public void execute()
         {
            xterm_ = new XTermWidget();
            xterm_.setHeight("100%");
            host_.add(xterm_);
            connectToTerminalProcess();
         }
      });
      return host_;
   }
  
   /**
    * Create a terminal process and connect to it.
    */
   private void connectToTerminalProcess()
   {
      server_.startShellDialog(ConsoleProcess.TerminalType.XTERM, 
                               80, 25,
                               new ServerRequestCallback<ConsoleProcess>()
      {
         @Override
         public void onResponseReceived(ConsoleProcess consoleProcess)
         {
            consoleProcess_ = consoleProcess;
            cryptoServer_ = server_;
            
            if (getInteractionMode() != ConsoleProcessInfo.INTERACTION_ALWAYS)
            {
               throw new IllegalArgumentException("Unsupport ConsoleProcess interaction mode");
            } 

            if (consoleProcess_ != null)
            {
               addHandlerRegistration(consoleProcess_.addConsoleOutputHandler(TerminalPane.this));
               addHandlerRegistration(consoleProcess_.addProcessExitHandler(TerminalPane.this));
               addHandlerRegistration(xterm_.addResizeTerminalHandler(TerminalPane.this));
               addHandlerRegistration(xterm_.addTerminalDataInputHandler(TerminalPane.this));

               consoleProcess.start(new SimpleRequestCallback<Void>()
               {
                  @Override
                  public void onError(ServerError error)
                  {
                     // Show error and stop
                     super.onError(error);

                     // TODO closeDialog();
                  }
               });
            }
         }
      
         @Override
         public void onError(ServerError error)
         {
            xterm_.writeln(error.getUserMessage());
         }
         
      });
   }

   private int getInteractionMode()
   {
      if (consoleProcess_ != null)
         return consoleProcess_.getProcessInfo().getInteractionMode();
      else
         return ConsoleProcessInfo.INTERACTION_NEVER;
   } 

   @Override
   public void onClick(ClickEvent event)
   {
      // TODO implement
      
   }

   @Override
   public void onConsoleOutput(ConsoleOutputEvent event)
   {
      xterm_.write(event.getOutput());
   }
   
   @Override
   public void onProcessExit(ProcessExitEvent event)
   {
      // TODO: implement
      
   }

   protected void addHandlerRegistration(HandlerRegistration reg)
   {
      registrations_.add(reg);
   }
   
   protected void unregisterHandlers()
   {
      // TODO: does this need to be called, and from where?
      registrations_.removeHandler();
   }
   
   // TODO: this is duplicated from ShellInteractionManager, pull out common behavior between
   // ConsoleProgressDialog and TerminalPane
   private void encryptInput(final String input, 
                             final CommandWithArg<String> onInputReady)
   {
      if (Desktop.isDesktop())
      {
         onInputReady.execute(input);
      }
      else if (publicKeyInfo_ != null)
      {
         RSAEncrypt.encrypt_ServerOnly(publicKeyInfo_, input, onInputReady);
      }
      else
      {
         cryptoServer_.getPublicKey(new ServerRequestCallback<PublicKeyInfo>() {

            @Override
            public void onResponseReceived(PublicKeyInfo publicKeyInfo)
            {
               publicKeyInfo_ = publicKeyInfo;
               RSAEncrypt.encrypt_ServerOnly(publicKeyInfo_, 
                                             input, 
                                             onInputReady);
            }
            
            @Override
            public void onError(ServerError error)
            {
               xterm_.writeln(error.getUserMessage());
            }
            
         });
      } 
   }
  
   // TODO: duplicated from ShellInteractionManager along with encryptInput; consolidate shared code
   private CommandWithArg<ShellInput> inputHandler_ = 
         new CommandWithArg<ShellInput>() 
   {
      @Override
      public void execute(ShellInput input)
      {         
         consoleProcess_.writeStandardInput(
               input, 
               new VoidServerRequestCallback() {
                  @Override
                  public void onError(ServerError error)
                  {
                     xterm_.writeln(error.getUserMessage());
                  }
               });
      }
   };
   
   @Override
   public void onResizeTerminal(ResizeTerminalEvent event)
   {
      consoleProcess_.resizeTerminal(
            event.getCols(), event.getRows(),
            new VoidServerRequestCallback() 
            {
               @Override
               public void onError(ServerError error)
               {
                  xterm_.writeln(error.getUserMessage());
               }
            });
   }
   
   @Override
   public void onTerminalDataInput(TerminalDataInputEvent event)
   {
       encryptInput(event.getData(), new CommandWithArg<String>()
       {
          @Override
          public void execute(String arg)
          {
             inputHandler_.execute(ShellInput.create(arg,  true /*echo input*/));
          }
       });
   } 
   
   private final ResizeLayoutPanel host_;
   private XTermWidget xterm_;
   private WorkbenchServerOperations server_;
   private CryptoServerOperations cryptoServer_;
   private ConsoleProcess consoleProcess_;
   private HandlerRegistrations registrations_ = new HandlerRegistrations();
   private PublicKeyInfo publicKeyInfo_ = null;
}