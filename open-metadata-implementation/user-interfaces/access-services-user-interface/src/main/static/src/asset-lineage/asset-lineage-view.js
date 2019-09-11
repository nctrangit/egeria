/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */

import { PolymerElement, html } from '@polymer/polymer/polymer-element.js';
import '../shared-styles.js';
import '../common/vis-graph.js';
import '@vaadin/vaadin-radio-button/vaadin-radio-button.js';
import '@vaadin/vaadin-radio-button/vaadin-radio-group.js';

class AssetLineageView extends PolymerElement {
  static get template() {
    return html`
    <style include="shared-styles">
        :host {
          display: block;
          padding: 10px;
        }
        
        .container {
          margin: auto; 
          height: calc(100vh - 130px);
          background-color: white;
        }
    </style>
      
    <token-ajax id="tokenAjax" last-response="{{graphData}}"></token-ajax>
    <vaadin-radio-group id ="radioUsecases" class="select-option-group" name="radio-group" value="ultimateSource"  role="radiogroup" >
      <vaadin-radio-button value="ultimateSource" class="select-option" role="radio" type="radio">Ultimate Source</vaadin-radio-button>
      <vaadin-radio-button value="endToEnd" class="select-option" role="radio" type="radio">End to End Lineage</vaadin-radio-button>
      <vaadin-radio-button value="ultimateDestination" class="select-option" role="radio" type="radio">Ultimate Destination</vaadin-radio-button>
      <vaadin-radio-button value="glossaryLineage" class="select-option" role="radio" type="radio">Glossary Lineage</vaadin-radio-button>
      <vaadin-radio-button value="sourceAndDestination" class="select-option" role="radio" type="radio">Source and Destination</vaadin-radio-button>
    </vaadin-radio-group>
          
    <div>
    <vaadin-radio-group id ="radioViews" class="select-option-group" name="radio-group" value="COLUMNVIEW"  role="radiogroup" >
      <vaadin-radio-button value="COLUMNVIEW" class="select-option" role="radio" type="radio">Column View</vaadin-radio-button>
      <vaadin-radio-button value="TABLEVIEW" class="select-option" role="radio" type="radio">Table view</vaadin-radio-button>
    </vaadin-radio-group>
    </div>
    
    <div class="container" id="container">
        <vis-graph id="visgraph" data=[[graphData]]></vis-graph>
    </div>
    `;
  }

    ready() {
        super.ready();
        this.$.radioUsecases.addEventListener('value-changed', () => this._usecaseChanged(this.$.radioUsecases.value, this.$.radioViews.value) );
    }

    static get properties() {
        return {
            guid: {
                type: String,
                observer: '_ultimateSource'
            },
            graphData: {
                type: Object,
                observer: '_graphDataChanged'
            },
            groups : {
                type: Object,
                value: {
                    GlossaryTerm: {
                        shape: 'diamond',
                        color: '#FCF68E'
                    },
                    Column: {
                        color: '#99E17E'
                    },
                    RelationalColumn: {
                        color: '#99E17E'
                    },
                    TabularColumn: {
                        color: '#99E17E'
                    },
                    RelationalTable: {
                        shape: 'box',
                    },
                    Process: {
                        shape: 'parallelogram'
                    },
                    condensedNode: {
                        color: '#71ccdc'
                    }
                }
            }
        }
    }


        _graphDataChanged(data) {
            console.log(data);
            if (data === null || data === undefined) {
                data ={ nodes : {},
                        edges  : {}
                        };
            } else {
                for (var i = 0; i < data.nodes.length; i++) {
                    data.nodes[i].title = JSON.stringify(data.nodes[i].properties, "test", '<br>');
                }
            }
            this.$.visgraph.importNodesAndEdges(data.nodes, data.edges);
        }


      _ultimateSource(guid, scope) {
          if (scope === null || scope === undefined) {
             scope  = "COLUMNVIEW";
          }
          this.$.visgraph.options.groups = this.groups;
          this.$.tokenAjax.url = '/api/lineage/entities/' + guid + '/ultimate-source?scope=' + scope;
          this.$.tokenAjax._go();
      }


      _endToEndLineage(guid, scope){
          if (scope === null || scope === undefined) {
              scope  = "COLUMNVIEW";
          }
          this.$.visgraph.options.groups = this.groups;
          this.$.tokenAjax.url = '/api/lineage/entities/' + guid+ '/end2end?scope=' + scope;
          this.$.tokenAjax._go();
      }

      _ultimateDestination(guid, scope){
          if (scope === null || scope === undefined) {
              scope  = "COLUMNVIEW";
          }
          this.$.visgraph.options.groups = this.groups;
          this.$.tokenAjax.url = '/api/lineage/entities/' + guid+ '/ultimate-destination?scope=' + scope;
          this.$.tokenAjax._go();
      }

      _glossaryLineage(guid, scope){
          if (scope === null || scope === undefined) {
              scope  = "COLUMNVIEW";
          }
          this.$.visgraph.options.groups = this.groups;
          this.$.tokenAjax.url = '/api/lineage/entities/' + guid+ '/glossary-lineage?scope=' + scope;
          this.$.tokenAjax._go();
      }

      _sourceAndDestination(guid, scope){
          if (scope === null || scope === undefined) {
              scope  = "COLUMNVIEW";
          }
          this.$.visgraph.options.groups = this.groups;
          this.$.tokenAjax.url = '/api/lineage/entities/' + guid+ '/source-and-destination?scope=' + scope;
          this.$.tokenAjax._go();
      }

    _usecaseChanged(value, scope) {
        switch (value) {
            case 'ultimateSource':
                this._ultimateSource(this.guid, scope);
                break;
            case 'endToEnd':
                this._endToEndLineage(this.guid, scope);
                break;
            case 'ultimateDestination':
                this._ultimateDestination(this.guid, scope);
                break;
            case 'glossaryLineage':
                this._glossaryLineage(this.guid, scope);
                break;
            case 'sourceAndDestination':
                this._sourceAndDestination(this.guid, scope);
                break;
        }
    }
}

window.customElements.define('asset-lineage-view', AssetLineageView);